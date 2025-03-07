package org.onedatashare.transferservice.odstransferservice.service.step.vfs;

import org.onedatashare.transferservice.odstransferservice.model.DataChunk;
import org.onedatashare.transferservice.odstransferservice.model.EntityInfo;
import org.onedatashare.transferservice.odstransferservice.model.FilePart;
import org.onedatashare.transferservice.odstransferservice.model.credential.AccountEndpointCredential;
import org.onedatashare.transferservice.odstransferservice.service.FilePartitioner;
import org.onedatashare.transferservice.odstransferservice.utility.ODSUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.annotation.BeforeStep;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.util.ClassUtils;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

import static org.onedatashare.transferservice.odstransferservice.constant.ODSConstants.SOURCE_BASE_PATH;

public class VfsReader extends AbstractItemCountingItemStreamItemReader<DataChunk> {

    FileChannel sink;
    Logger logger = LoggerFactory.getLogger(VfsReader.class);
    FileInputStream inputStream;
    String sBasePath;
    String fileName;
    FilePartitioner filePartitioner;
    EntityInfo fileInfo;
    AccountEndpointCredential credential;
    ConcurrentHashMap<Long, ByteBuffer> bufferMap;

    public VfsReader(AccountEndpointCredential credential, EntityInfo fInfo) {
        this.setExecutionContextName(ClassUtils.getShortName(VfsReader.class));
        this.credential = credential;
        this.filePartitioner = new FilePartitioner(fInfo.getChunkSize());
        this.fileInfo = fInfo;
        bufferMap = new ConcurrentHashMap<>();
    }

    @BeforeStep
    public void beforeStep(StepExecution stepExecution) {
        logger.info("Before step for : " + stepExecution.getStepName());
        JobParameters params = stepExecution.getJobExecution().getJobParameters();
        this.sBasePath = params.getString(SOURCE_BASE_PATH);
        this.fileName = this.fileInfo.getId();
        this.filePartitioner.createParts(this.fileInfo.getSize(), fileName);
    }

    @Override
    protected DataChunk doRead() {
        FilePart chunkParameters = this.filePartitioner.nextPart();
        if (chunkParameters == null) return null;// done as there are no more FileParts in the queue
        logger.info("currently reading {}", chunkParameters);
        int totalBytes = 0;
        ByteBuffer buffer = bufferMap.get(Thread.currentThread().getId());
        if(buffer == null){
            buffer = ByteBuffer.allocate(chunkParameters.getSize());
            bufferMap.put(Thread.currentThread().getId(), buffer);
        }
        while (totalBytes < chunkParameters.getSize()) {
            int bytesRead = 0;
            try {
                bytesRead = this.sink.read(buffer, chunkParameters.getStart()+totalBytes);
            } catch (IOException ex) {
                logger.error("Unable to read from source");
                ex.printStackTrace();
            }
            if (bytesRead == -1) return null;
            totalBytes += bytesRead;
        }
        buffer.flip();
        if(chunkParameters.getSize() != totalBytes){
            logger.info("We read in {} bytes and expected to read in {} bytes", chunkParameters.getSize(), totalBytes);
        }
        byte[] data = new byte[chunkParameters.getSize()];
        buffer.get(data, 0, totalBytes);
        buffer.clear();
        return ODSUtility.makeChunk(totalBytes, data, chunkParameters.getStart(), Long.valueOf(chunkParameters.getPartIdx()).intValue(), this.fileName);
    }

    @Override
    protected void doOpen() {
        logger.info("Starting Open in VFS");
        try {
            this.inputStream = new FileInputStream(Paths.get(this.fileInfo.getPath()).toString());
        } catch (FileNotFoundException e) {
            logger.error("Path not found : " + this.fileInfo.getPath());
            e.printStackTrace();
        }
        this.sink = this.inputStream.getChannel();
    }

    @Override
    protected void doClose() {
        try {
            if (inputStream != null) inputStream.close();
            if (sink.isOpen()) sink.close();
        } catch (Exception ex) {
            logger.error("Not able to close the input Stream");
            ex.printStackTrace();
        }
        this.bufferMap.clear();
    }
}
