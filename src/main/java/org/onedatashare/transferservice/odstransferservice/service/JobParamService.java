package org.onedatashare.transferservice.odstransferservice.service;

import io.micrometer.core.instrument.Gauge;
import org.onedatashare.transferservice.odstransferservice.model.EntityInfo;
import org.onedatashare.transferservice.odstransferservice.model.TransferJobRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.stereotype.Service;


import static org.onedatashare.transferservice.odstransferservice.constant.ODSConstants.*;
import static org.onedatashare.transferservice.odstransferservice.constant.ODSConstants.DEST_BASE_PATH;

@Service
public class JobParamService {

    Logger logger = LoggerFactory.getLogger(JobParamService.class);


    public JobParameters translate(JobParametersBuilder builder, TransferJobRequest request) {
        logger.info("Setting job Parameters");
        builder.addLong(TIME, System.currentTimeMillis());
        builder.addString(OWNER_ID, request.getOwnerId());
        builder.addString(PRIORITY, String.valueOf(request.getPriority()));
        builder.addString(CHUNK_SIZE, String.valueOf(request.getChunkSize()));
        builder.addString(SOURCE_BASE_PATH, request.getSource().getParentInfo().getPath());
        builder.addString(DEST_BASE_PATH, request.getDestination().getParentInfo().getPath());
        builder.addString(SOURCE_CREDENTIAL_ID, request.getSource().getCredId());
        builder.addString(DEST_CREDENTIAL_ID, request.getDestination().getCredId());
        builder.addString(SOURCE_CREDENTIAL_TYPE, request.getSource().getType().toString());
        builder.addString(DEST_CREDENTIAL_TYPE, request.getDestination().getType().toString());
        builder.addString(COMPRESS, String.valueOf(request.getOptions().getCompress()));
        builder.addLong(CONCURRENCY, (long) request.getOptions().getConcurrencyThreadCount());
        builder.addLong(PARALLELISM, (long) request.getOptions().getParallelThreadCount());
        builder.addLong(PIPELINING, (long) request.getOptions().getPipeSize());
        builder.addLong(RETRY, (long) request.getOptions().getRetry());
        builder.addString(APP_NAME, System.getenv("APP_NAME"));
        builder.addLong(PIPELINING, (long) request.getOptions().getPipeSize());
        builder.addString(OPTIMIZER, request.getOptions().getOptimizer());
        builder.addLong(FILE_COUNT, (long) request.getSource().getInfoList().size());
        long totalSize = 0L;
        for(EntityInfo fileInfo : request.getSource().getInfoList()){
            builder.addString(fileInfo.getId(), fileInfo.toString());
            totalSize+=fileInfo.getSize();
        }
        builder.addLong(JOB_SIZE, totalSize);
        double value = 0;
        if(request.getSource().getInfoList().size() > 0){
            value = totalSize/ (double)request.getSource().getInfoList().size();
        }
        builder.addLong(FILE_SIZE_AVG, (long) value);
        return builder.toJobParameters();
    }
}
