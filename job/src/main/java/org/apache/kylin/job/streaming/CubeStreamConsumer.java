package org.apache.kylin.job.streaming;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.io.IOUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.hll.HyperLogLogPlusCounter;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.CubeUpdate;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.gridtable.CuboidToGridTableMapping;
import org.apache.kylin.cube.inmemcubing.ICuboidWriter;
import org.apache.kylin.cube.inmemcubing.InMemCubeBuilder;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.HBaseColumnDesc;
import org.apache.kylin.cube.model.HBaseColumnFamilyDesc;
import org.apache.kylin.cube.util.CubingUtils;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.dict.DictionaryInfo;
import org.apache.kylin.dict.DictionaryManager;
import org.apache.kylin.engine.mr.HadoopUtil;
import org.apache.kylin.engine.mr.common.BatchConstants;
import org.apache.kylin.engine.mr.steps.FactDistinctColumnsReducer;
import org.apache.kylin.gridtable.GTRecord;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.source.ReadableTable.TableSignature;
import org.apache.kylin.storage.hbase.HBaseConnection;
import org.apache.kylin.storage.hbase.steps.CubeHTableUtil;
import org.apache.kylin.storage.hbase.steps.InMemKeyValueCreator;
import org.apache.kylin.streaming.MicroStreamBatch;
import org.apache.kylin.streaming.MicroStreamBatchConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 */
public class CubeStreamConsumer implements MicroStreamBatchConsumer {

    private static final Logger logger = LoggerFactory.getLogger(CubeStreamConsumer.class);

    private final CubeManager cubeManager;
    private final String cubeName;
    private final KylinConfig kylinConfig;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private static final int BATCH_PUT_THRESHOLD = 10000;
    private int totalConsumedMessageCount = 0;
    private int totalRawMessageCount = 0;

    public CubeStreamConsumer(String cubeName) {
        this.kylinConfig = KylinConfig.getInstanceFromEnv();
        this.cubeManager = CubeManager.getInstance(kylinConfig);
        this.cubeName = cubeName;
    }

    @Override
    public void consume(MicroStreamBatch microStreamBatch) throws Exception {

        totalConsumedMessageCount += microStreamBatch.size();
        totalRawMessageCount += microStreamBatch.getRawMessageCount();

        final List<List<String>> parsedStreamMessages = microStreamBatch.getStreams();
        long startOffset = microStreamBatch.getOffset().getFirst();
        long endOffset = microStreamBatch.getOffset().getSecond();
        LinkedBlockingQueue<List<String>> blockingQueue = new LinkedBlockingQueue<List<String>>(parsedStreamMessages);
        blockingQueue.put(Collections.<String> emptyList());

        final CubeInstance cubeInstance = cubeManager.reloadCubeLocal(cubeName);
        final CubeDesc cubeDesc = cubeInstance.getDescriptor();
        final CubeSegment cubeSegment = cubeManager.appendSegments(cubeManager.getCube(cubeName), microStreamBatch.getTimestamp().getFirst(), microStreamBatch.getTimestamp().getSecond(), false, false);
        long start = System.currentTimeMillis();
        final Map<Long, HyperLogLogPlusCounter> samplingResult = CubingUtils.sampling(cubeInstance.getDescriptor(), parsedStreamMessages);
        logger.info(String.format("sampling of %d messages cost %d ms", parsedStreamMessages.size(), (System.currentTimeMillis() - start)));

        final Configuration conf = HadoopUtil.getCurrentConfiguration();
        final Path outputPath = new Path("file:///tmp/cuboidstatistics/" + UUID.randomUUID().toString());
        FileSystem.getLocal(conf).deleteOnExit(outputPath);
        FactDistinctColumnsReducer.writeCuboidStatistics(conf, outputPath, samplingResult, 100);
        FSDataInputStream localStream = FileSystem.getLocal(conf).open(new Path(outputPath, BatchConstants.CFG_STATISTICS_CUBOID_ESTIMATION));
        ResourceStore.getStore(kylinConfig).putResource(cubeSegment.getStatisticsResourcePath(), localStream, 0);
        localStream.close();

        final Map<TblColRef, Dictionary<?>> dictionaryMap = CubingUtils.buildDictionary(cubeInstance, parsedStreamMessages);
        Map<TblColRef, Dictionary<?>> realDictMap = writeDictionary(cubeSegment, dictionaryMap, startOffset, endOffset);

        InMemCubeBuilder inMemCubeBuilder = new InMemCubeBuilder(cubeInstance.getDescriptor(), realDictMap);
        final HTableInterface hTable = createHTable(cubeSegment);
        final CubeStreamRecordWriter gtRecordWriter = new CubeStreamRecordWriter(cubeDesc, hTable);

        executorService.submit(inMemCubeBuilder.buildAsRunnable(blockingQueue, gtRecordWriter)).get();
        gtRecordWriter.flush();
        hTable.close();
        commitSegment(cubeSegment);

        logger.info("Consumed {} messages out of {} raw messages", totalConsumedMessageCount, totalRawMessageCount);
    }

    private Map<TblColRef, Dictionary<?>> writeDictionary(CubeSegment cubeSegment, Map<TblColRef, Dictionary<?>> dictionaryMap, long startOffset, long endOffset) {
        Map<TblColRef, Dictionary<?>> realDictMap = Maps.newHashMap();

        for (Map.Entry<TblColRef, Dictionary<?>> entry : dictionaryMap.entrySet()) {
            final TblColRef tblColRef = entry.getKey();
            final Dictionary<?> dictionary = entry.getValue();
            TableSignature signature = new TableSignature();
            signature.setLastModifiedTime(System.currentTimeMillis());
            signature.setPath(String.format("streaming_%s_%s", startOffset, endOffset));
            signature.setSize(endOffset - startOffset);
            DictionaryInfo dictInfo = new DictionaryInfo(tblColRef.getTable(), tblColRef.getName(), tblColRef.getColumnDesc().getZeroBasedIndex(), tblColRef.getDatatype(), signature);
            logger.info("writing dictionary for TblColRef:" + tblColRef.toString());
            DictionaryManager dictionaryManager = DictionaryManager.getInstance(kylinConfig);
            try {
                DictionaryInfo realDict = dictionaryManager.trySaveNewDict(dictionary, dictInfo);
                cubeSegment.putDictResPath(tblColRef, realDict.getResourcePath());
                realDictMap.put(tblColRef, realDict.getDictionaryObject());
            } catch (IOException e) {
                logger.error("error save dictionary for column:" + tblColRef, e);
                throw new RuntimeException("error save dictionary for column:" + tblColRef, e);
            }
        }

        return realDictMap;
    }

    private class CubeStreamRecordWriter implements ICuboidWriter {
        final List<InMemKeyValueCreator> keyValueCreators;
        final int nColumns;
        final HTableInterface hTable;
        private final ByteBuffer byteBuffer;
        private final CubeDesc cubeDesc;
        private List<Put> puts = Lists.newArrayList();

        private CubeStreamRecordWriter(CubeDesc cubeDesc, HTableInterface hTable) {
            this.keyValueCreators = Lists.newArrayList();
            this.cubeDesc = cubeDesc;
            int startPosition = 0;
            for (HBaseColumnFamilyDesc cfDesc : cubeDesc.getHBaseMapping().getColumnFamily()) {
                for (HBaseColumnDesc colDesc : cfDesc.getColumns()) {
                    keyValueCreators.add(new InMemKeyValueCreator(colDesc, startPosition));
                    startPosition += colDesc.getMeasures().length;
                }
            }
            this.nColumns = keyValueCreators.size();
            this.hTable = hTable;
            this.byteBuffer = ByteBuffer.allocate(1 << 20);
        }

        private byte[] copy(byte[] array, int offset, int length) {
            byte[] result = new byte[length];
            System.arraycopy(array, offset, result, 0, length);
            return result;
        }

        private ByteBuffer createKey(Long cuboidId, GTRecord record) {
            byteBuffer.clear();
            byteBuffer.put(Bytes.toBytes(cuboidId));
            final int cardinality = BitSet.valueOf(new long[] { cuboidId }).cardinality();
            for (int i = 0; i < cardinality; i++) {
                final ByteArray byteArray = record.get(i);
                byteBuffer.put(byteArray.array(), byteArray.offset(), byteArray.length());
            }
            return byteBuffer;
        }

        @Override
        public void write(long cuboidId, GTRecord record) throws IOException {
            final ByteBuffer key = createKey(cuboidId, record);
            final CuboidToGridTableMapping mapping = new CuboidToGridTableMapping(Cuboid.findById(cubeDesc, cuboidId));
            final ImmutableBitSet bitSet = new ImmutableBitSet(mapping.getDimensionCount(), mapping.getColumnCount());
            for (int i = 0; i < nColumns; i++) {
                final KeyValue keyValue = keyValueCreators.get(i).create(key.array(), 0, key.position(), record.getValues(bitSet, new Object[bitSet.cardinality()]));
                final Put put = new Put(copy(key.array(), 0, key.position()));
                byte[] family = copy(keyValue.getFamilyArray(), keyValue.getFamilyOffset(), keyValue.getFamilyLength());
                byte[] qualifier = copy(keyValue.getQualifierArray(), keyValue.getQualifierOffset(), keyValue.getQualifierLength());
                byte[] value = copy(keyValue.getValueArray(), keyValue.getValueOffset(), keyValue.getValueLength());
                put.add(family, qualifier, value);
                puts.add(put);
            }
            if (puts.size() >= BATCH_PUT_THRESHOLD) {
                flush();
            }
        }

        public final void flush() {
            try {
                if (!puts.isEmpty()) {
                    long t = System.currentTimeMillis();
                    if (hTable != null) {
                        hTable.put(puts);
                        hTable.flushCommits();
                    }
                    logger.info("commit total " + puts.size() + " puts, totally cost:" + (System.currentTimeMillis() - t) + "ms");
                    puts.clear();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    //TODO: should we use cubeManager.promoteNewlyBuiltSegments?
    private void commitSegment(CubeSegment cubeSegment) throws IOException {
        cubeSegment.setStatus(SegmentStatusEnum.READY);
        CubeUpdate cubeBuilder = new CubeUpdate(cubeSegment.getCubeInstance());
        cubeBuilder.setToAddSegs(cubeSegment);
        CubeManager.getInstance(kylinConfig).updateCube(cubeBuilder);
    }

    private HTableInterface createHTable(final CubeSegment cubeSegment) throws Exception {
        final String hTableName = cubeSegment.getStorageLocationIdentifier();
        CubeHTableUtil.createHTable(cubeSegment.getCubeDesc(), hTableName, null);
        final HTableInterface hTable = HBaseConnection.get(KylinConfig.getInstanceFromEnv().getStorageUrl()).getTable(hTableName);
        logger.info("hTable:" + hTableName + " for segment:" + cubeSegment.getName() + " created!");
        return hTable;
    }

    @Override
    public void stop() {

    }

}
