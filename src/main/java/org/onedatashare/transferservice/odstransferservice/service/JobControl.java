package org.onedatashare.transferservice.odstransferservice.service;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.onedatashare.transferservice.odstransferservice.Enum.EndpointType;
import org.onedatashare.transferservice.odstransferservice.model.DataChunk;
import org.onedatashare.transferservice.odstransferservice.model.EntityInfo;
import org.onedatashare.transferservice.odstransferservice.model.TransferJobRequest;
import org.onedatashare.transferservice.odstransferservice.pools.ThreadPoolManager;
import org.onedatashare.transferservice.odstransferservice.service.cron.MetricsCollector;
import org.onedatashare.transferservice.odstransferservice.service.listner.JobCompletionListener;
import org.onedatashare.transferservice.odstransferservice.service.step.AmazonS3.AmazonS3LargeFileWriter;
import org.onedatashare.transferservice.odstransferservice.service.step.AmazonS3.AmazonS3Reader;
import org.onedatashare.transferservice.odstransferservice.service.step.AmazonS3.AmazonS3SmallFileWriter;
import org.onedatashare.transferservice.odstransferservice.service.step.box.BoxReader;
import org.onedatashare.transferservice.odstransferservice.service.step.box.BoxWriterLargeFile;
import org.onedatashare.transferservice.odstransferservice.service.step.box.BoxWriterSmallFile;
import org.onedatashare.transferservice.odstransferservice.service.step.dropbox.DropBoxChunkedWriter;
import org.onedatashare.transferservice.odstransferservice.service.step.dropbox.DropBoxReader;
import org.onedatashare.transferservice.odstransferservice.service.step.ftp.FTPReader;
import org.onedatashare.transferservice.odstransferservice.service.step.ftp.FTPWriter;
import org.onedatashare.transferservice.odstransferservice.service.step.http.HttpReader;
import org.onedatashare.transferservice.odstransferservice.service.step.scp.SCPReader;
import org.onedatashare.transferservice.odstransferservice.service.step.scp.SCPWriter;
import org.onedatashare.transferservice.odstransferservice.service.step.sftp.SFTPReader;
import org.onedatashare.transferservice.odstransferservice.service.step.sftp.SFTPWriter;
import org.onedatashare.transferservice.odstransferservice.service.step.vfs.VfsReader;
import org.onedatashare.transferservice.odstransferservice.service.step.vfs.VfsWriter;
import org.onedatashare.transferservice.odstransferservice.utility.ODSUtility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.configuration.annotation.DefaultBatchConfigurer;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.job.builder.FlowBuilder;
import org.springframework.batch.core.job.flow.Flow;
import org.springframework.batch.core.job.flow.support.SimpleFlow;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.AbstractItemCountingItemStreamItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static org.onedatashare.transferservice.odstransferservice.constant.ODSConstants.TWENTY_MB;


@Service
@NoArgsConstructor
@Getter
@Setter
public class JobControl extends DefaultBatchConfigurer {

    public TransferJobRequest request;

    Logger logger = LoggerFactory.getLogger(JobControl.class);

    @Value("${spring.application.name}")
    String appName;

    @Autowired
    ThreadPoolManager threadPoolManager;

    @Autowired
    VfsExpander vfsExpander;

    @Autowired
    JobBuilderFactory jobBuilderFactory;

    @Autowired
    StepBuilderFactory stepBuilderFactory;

    @Autowired
    ConnectionBag connectionBag;

    @Autowired
    JobCompletionListener jobCompletionListener;

    @Autowired
    MetricsCollector metricsCollector;

    @Autowired
    MetricCache metricCache;

    @Autowired
    RetryTemplate retryTemplateForReaderAndWriter;

    @Autowired
    JobRepository roachRepository;

    @Lazy
    @Bean
    public JobLauncher asyncJobLauncher() {
        SimpleJobLauncher jobLauncher = new SimpleJobLauncher();
        jobLauncher.setJobRepository(roachRepository);
        jobLauncher.setTaskExecutor(this.threadPoolManager.sequentialThreadPool());
        return jobLauncher;
    }

    // TODO : parallelism - Kavi
    private List<Flow> createConcurrentFlow(List<EntityInfo> infoList, String basePath) {
        List<Flow> flows = new ArrayList<>();
        if (this.request.getSource().getType().equals(EndpointType.vfs)) {
            infoList = vfsExpander.expandDirectory(infoList, basePath, this.request.getChunkSize());
            logger.info("File list: {}", infoList);
        }
        return infoList.stream().map(file -> {
            String idForStep = "";
            if (!file.getId().isEmpty()) {
                idForStep = file.getId();
            } else {
                idForStep = file.getPath();
            }
            SimpleStepBuilder<DataChunk, DataChunk> child = stepBuilderFactory.get(idForStep).<DataChunk, DataChunk>chunk(this.request.getOptions().getPipeSize());
            if (ODSUtility.fullyOptimizableProtocols.contains(this.request.getSource().getType()) && ODSUtility.fullyOptimizableProtocols.contains(this.request.getDestination().getType()) && this.request.getOptions().getParallelThreadCount() > 0) {
                child.taskExecutor(this.threadPoolManager.parallelThreadPool(request.getOptions().getParallelThreadCount(), idForStep));
            }
            child.reader(getRightReader(request.getSource().getType(), file))
                    .writer(getRightWriter(request.getDestination().getType(), file));
            child.throttleLimit(32); //this value might allow concurrency to be dynamic.
            logger.info("Creating step with id {} ", idForStep);
//            flows.add(new FlowBuilder<Flow>(basePath + idForStep).start(child.build()).build());
            return new FlowBuilder<Flow>(basePath + idForStep).start(child.build()).build();
        }).collect(Collectors.toList());
//        return flows;
    }

    protected AbstractItemCountingItemStreamItemReader<DataChunk> getRightReader(EndpointType type, EntityInfo fileInfo) {
        switch (type) {
            case http:
                HttpReader hr = new HttpReader(fileInfo, request.getSource().getVfsSourceCredential());
                hr.setPool(connectionBag.getHttpReaderPool());
                return hr;
            case vfs:
                VfsReader vfsReader = new VfsReader(request.getSource().getVfsSourceCredential(), fileInfo);
                return vfsReader;
            case sftp:
                SFTPReader sftpReader = new SFTPReader(request.getSource().getVfsSourceCredential(), fileInfo, request.getOptions().getPipeSize());
                sftpReader.setPool(connectionBag.getSftpReaderPool());
                sftpReader.setRetryTemplate(retryTemplateForReaderAndWriter);
                return sftpReader;
            case ftp:
                FTPReader ftpReader = new FTPReader(request.getSource().getVfsSourceCredential(), fileInfo);
                ftpReader.setPool(connectionBag.getFtpReaderPool());
                return ftpReader;
            case s3:
                AmazonS3Reader amazonS3Reader = new AmazonS3Reader(request.getSource().getVfsSourceCredential(), fileInfo);
                amazonS3Reader.setPool(connectionBag.getS3ReaderPool());
                return amazonS3Reader;
            case box:
                BoxReader boxReader = new BoxReader(request.getSource().getOauthSourceCredential(), fileInfo);
                boxReader.setMaxRetry(this.request.getOptions().getRetry());
                return boxReader;
            case dropbox:
                DropBoxReader dropBoxReader = new DropBoxReader(request.getSource().getOauthSourceCredential(), fileInfo);
                return dropBoxReader;
            case scp:
                SCPReader reader = new SCPReader(fileInfo);
                reader.setPool(connectionBag.getSftpReaderPool());
                return reader;
        }
        return null;
    }

    protected ItemWriter<DataChunk> getRightWriter(EndpointType type, EntityInfo fileInfo) {
        switch (type) {
            case vfs:
                VfsWriter vfsWriter = new VfsWriter(request.getDestination().getVfsDestCredential(), fileInfo);
                vfsWriter.setMetricsCollector(metricsCollector);
                vfsWriter.setMetricCache(metricCache);
                return vfsWriter;
            case sftp:
                SFTPWriter sftpWriter = new SFTPWriter(request.getDestination().getVfsDestCredential(), request.getOptions().getPipeSize());
                sftpWriter.setPool(connectionBag.getSftpWriterPool());
                sftpWriter.setMetricsCollector(metricsCollector);
                sftpWriter.setMetricCache(metricCache);
                sftpWriter.setRetryTemplate(retryTemplateForReaderAndWriter);
                return sftpWriter;
            case ftp:
                FTPWriter ftpWriter = new FTPWriter(request.getDestination().getVfsDestCredential());
                ftpWriter.setPool(connectionBag.getFtpWriterPool());
                ftpWriter.setMetricsCollector(metricsCollector);
                ftpWriter.setMetricCache(metricCache);
                ftpWriter.setRetryTemplate(retryTemplateForReaderAndWriter);
                return ftpWriter;
            case s3:
                if (fileInfo.getSize() < TWENTY_MB) {
                    AmazonS3SmallFileWriter amazonS3SmallFileWriter = new AmazonS3SmallFileWriter(request.getDestination().getVfsDestCredential(), fileInfo);
                    amazonS3SmallFileWriter.setMetricCache(this.metricCache);
                    amazonS3SmallFileWriter.setMetricsCollector(metricsCollector);
                    amazonS3SmallFileWriter.setPool(connectionBag.getS3WriterPool());
                    return amazonS3SmallFileWriter;
                } else {
                    AmazonS3LargeFileWriter amazonS3LargeFileWriter = new AmazonS3LargeFileWriter(request.getDestination().getVfsDestCredential(), fileInfo);
                    amazonS3LargeFileWriter.setMetricCache(this.metricCache);
                    amazonS3LargeFileWriter.setMetricsCollector(metricsCollector);
                    amazonS3LargeFileWriter.setPool(connectionBag.getS3WriterPool());
                    return amazonS3LargeFileWriter;
                }
            case box:
                if (fileInfo.getSize() < TWENTY_MB) {
                    BoxWriterSmallFile boxWriterSmallFile = new BoxWriterSmallFile(request.getDestination().getOauthDestCredential(), fileInfo);
                    boxWriterSmallFile.setMetricsCollector(metricsCollector);
                    boxWriterSmallFile.setMetricCache(metricCache);
                    return boxWriterSmallFile;
                } else {
                    BoxWriterLargeFile boxWriterLargeFile = new BoxWriterLargeFile(request.getDestination().getOauthDestCredential(), fileInfo);
                    boxWriterLargeFile.setMetricsCollector(metricsCollector);
                    boxWriterLargeFile.setMetricCache(metricCache);
                    return boxWriterLargeFile;
                }
            case dropbox:
                DropBoxChunkedWriter dropBoxChunkedWriter = new DropBoxChunkedWriter(request.getDestination().getOauthDestCredential());
                dropBoxChunkedWriter.setMetricsCollector(metricsCollector);
                dropBoxChunkedWriter.setMetricCache(metricCache);
                return dropBoxChunkedWriter;
            case scp:
                SCPWriter scpWriter = new SCPWriter(fileInfo);
                scpWriter.setPool(connectionBag.getSftpWriterPool());
                scpWriter.setMetricsCollector(metricsCollector);
                scpWriter.setMetricCache(metricCache);
                return scpWriter;
        }
        return null;
    }

    public Job concurrentJobDefinition() {
        connectionBag.preparePools(this.request);
        List<Flow> flows = createConcurrentFlow(request.getSource().getInfoList(), request.getSource().getParentInfo().getPath());
        setRetryPolicy();
        Flow[] fl = new Flow[flows.size()];
        Flow f = new FlowBuilder<SimpleFlow>("splitFlow").split(this.threadPoolManager.stepTaskExecutor(this.request.getOptions().getConcurrencyThreadCount())).add(flows.toArray(fl))
                .build();
        return jobBuilderFactory.get(request.getOwnerId()).listener(jobCompletionListener)
                .incrementer(new RunIdIncrementer()).start(f).build().build();
    }

    private void setRetryPolicy() {
        Map<Class<? extends Throwable>, Boolean> retryFor = new HashMap<>();
        retryFor.put(IOException.class, true);
        //add other exceptions to retry for in the map above.
        int retryAttempts = ofNullable(this.request.getOptions().getRetry()).orElse(1);
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(retryAttempts, retryFor);
        this.retryTemplateForReaderAndWriter.setRetryPolicy(retryPolicy);
    }
}