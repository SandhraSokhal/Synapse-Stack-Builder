package org.sagebionetworks.template.datawarehouse.backfill;

import com.amazonaws.internal.ReleasableInputStream;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.athena.model.Datum;
import com.amazonaws.services.athena.model.GetQueryExecutionRequest;
import com.amazonaws.services.athena.model.GetQueryExecutionResult;
import com.amazonaws.services.athena.model.GetQueryResultsRequest;
import com.amazonaws.services.athena.model.GetQueryResultsResult;
import com.amazonaws.services.athena.model.QueryExecutionContext;
import com.amazonaws.services.athena.model.QueryExecutionStatus;
import com.amazonaws.services.athena.model.ResultConfiguration;
import com.amazonaws.services.athena.model.ResultSetMetadata;
import com.amazonaws.services.athena.model.Row;
import com.amazonaws.services.athena.model.StartQueryExecutionRequest;
import com.amazonaws.services.athena.model.StartQueryExecutionResult;
import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.glue.model.BatchCreatePartitionRequest;
import com.amazonaws.services.glue.model.GetTableRequest;
import com.amazonaws.services.glue.model.GetTableResult;
import com.amazonaws.services.glue.model.PartitionInput;
import com.amazonaws.services.glue.model.StartJobRunRequest;
import com.amazonaws.services.glue.model.StorageDescriptor;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.json.JSONObject;
import org.sagebionetworks.template.CloudFormationClient;
import org.sagebionetworks.template.CreateOrUpdateStackRequest;
import org.sagebionetworks.template.LoggerFactory;
import org.sagebionetworks.template.StackTagsProvider;
import org.sagebionetworks.template.config.Configuration;
import org.sagebionetworks.template.datawarehouse.DataWarehouseBuilderImpl;
import org.sagebionetworks.template.datawarehouse.EtlJobConfig;
import org.sagebionetworks.template.repo.VelocityExceptionThrower;
import org.sagebionetworks.template.utils.ArtifactDownload;
import org.sagebionetworks.util.ValidateArgument;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.util.Map.entry;
import static org.sagebionetworks.template.Constants.CAPABILITY_NAMED_IAM;
import static org.sagebionetworks.template.Constants.EXCEPTION_THROWER;
import static org.sagebionetworks.template.Constants.GLUE_DATABASE_NAME;
import static org.sagebionetworks.template.Constants.JSON_INDENT;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_DATAWAREHOUSE_GLUE_DATABASE_NAME;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_STACK;
import static org.sagebionetworks.template.Constants.STACK;

public class BackfillWarehouseBuilderImpl implements BackfillWarehouseBuilder {
    private static final String S3_GLUE_BUCKET = "aws-glue.sagebase.org";
    public static final String TEMPLATE_ETL_GLUE_JOB_RESOURCES = "templates/datewarehouse/backfill/backfill-etl-jobs-template.json.vpt";
    private static final String S3_BACKFILL_KEY_PATH_TPL = "scripts/backfill/";
    private static final String GITHUB_URL_TPL = "https://codeload.github.com/Sage-Bionetworks/%s/zip/refs/tags/v%s";
    private static final String SCRIPT_PATH_TPL = "%s-%s/src/scripts/backfill_jobs/";
    private static final String GS_EXPLODE_SCRIPT = "s3://aws-glue-studio-transforms-510798373988-prod-us-east-1/gs_explode.py";
    private static final String GS_COMMON_SCRIPT = "s3://aws-glue-studio-transforms-510798373988-prod-us-east-1/gs_common.py";
    private static final String BACK_FILL_YEAR ="org.sagebionetworks.synapse.datawarehouse.glue.backfill.year";
    private static final String BACK_FILL_MONTH ="org.sagebionetworks.synapse.datawarehouse.glue.backfill.month";
    private static final String BACK_FILL_DAY ="org.sagebionetworks.synapse.datawarehouse.glue.backfill.day";
    private static final Map<String, String> tableToMidMap = ImmutableMap.ofEntries(
            entry("bulkfiledownloadresponse","bulkfiledownloadscsv"),
            entry("filedownloadrecord","filedownloadscsv"));
    private EtlJobConfig etlJobConfig;
    private ArtifactDownload downloader;
    private Configuration config;
    private Logger logger;
    private VelocityEngine velocityEngine;

    private AmazonS3 s3Client;
    private CloudFormationClient cloudFormationClient;
    private StackTagsProvider tagsProvider;
    private AWSGlue awsGlue;
    private AmazonAthena athena;

    @Inject
    public BackfillWarehouseBuilderImpl(CloudFormationClient cloudFormationClient, VelocityEngine velocityEngine,
                                    Configuration config, LoggerFactory loggerFactory,
                                    StackTagsProvider tagsProvider, EtlJobConfig etlJobConfig, ArtifactDownload downloader,
                                        AmazonS3 s3Client, AWSGlue awsGlue, AmazonAthena athena) {
        this.cloudFormationClient = cloudFormationClient;
        this.velocityEngine = velocityEngine;
        this.config = config;
        this.logger = loggerFactory.getLogger(DataWarehouseBuilderImpl.class);
        this.tagsProvider = tagsProvider;
        this.etlJobConfig = etlJobConfig;
        this.downloader = downloader;
        this.s3Client = s3Client;
        this.awsGlue = awsGlue;
        this.athena = athena;
    }

    @Override
    public void buildAndDeploy() {
        String databaseName = config.getProperty(PROPERTY_KEY_DATAWAREHOUSE_GLUE_DATABASE_NAME);
        String backfillYear = config.getProperty(BACK_FILL_YEAR);
        String backfillMonth = config.getProperty(BACK_FILL_MONTH);
        String backfillDay = config.getProperty(BACK_FILL_DAY);
        String backfillStartDate = String.join("-",backfillYear,"01","01");
        String backfillEndDate = String.join("-",backfillYear,backfillMonth,backfillDay);
        ValidateArgument.requiredNotEmpty(databaseName, "The database name");
        databaseName = databaseName.toLowerCase();

        String stack = config.getProperty(PROPERTY_KEY_STACK);
        String bucket = String.join(".", stack, S3_GLUE_BUCKET);
        String scriptLocationPrefix = bucket + "/" + copyArtifactFromGithub(bucket);

        VelocityContext context = new VelocityContext();

        context.put(GLUE_DATABASE_NAME, databaseName);
        context.put(EXCEPTION_THROWER, new VelocityExceptionThrower());
        context.put(STACK, stack);
        context.put("scriptLocationPrefix", scriptLocationPrefix);
        String utilscript = "s3://"+ scriptLocationPrefix + "backfill_utils.py";
        List<String> extraScripts = new ArrayList<>();
        extraScripts.add(utilscript);
        extraScripts.add(GS_EXPLODE_SCRIPT);
        extraScripts.add(GS_COMMON_SCRIPT);
        context.put("extraScripts", String.join(",", extraScripts));
        Template template = this.velocityEngine.getTemplate(TEMPLATE_ETL_GLUE_JOB_RESOURCES);

        StringWriter stringWriter = new StringWriter();
        template.merge(context, stringWriter);
        // Parse the resulting template
        String resultJSON = stringWriter.toString();
        JSONObject templateJson = new JSONObject(resultJSON);

        // Format the JSON
        resultJSON = templateJson.toString(JSON_INDENT);
        this.logger.info(resultJSON);
        // create or update the stack
        String stackName = new StringJoiner("-").add(stack).add(databaseName).add("backfill-etl-jobs").toString();
        this.cloudFormationClient.createOrUpdateStack(new CreateOrUpdateStackRequest().withStackName(stackName)
                .withTemplateBody(resultJSON).withTags(tagsProvider.getStackTags())
                .withCapabilities(CAPABILITY_NAMED_IAM));

       // createGluePartitionForOldData("", stack+ ".snapshot.record.sagebase.org", "backfill");
       // List<String> glueJobInputList = getAthenaResult(backfillYear);
        /*for( String instance :glueJobInputList){
            startAWSGLueJob("sandhra_backfill_old_datawarehouse_filedownload_records",instance,"dev", "sandhra","bulkfiledownloadscsv","filedownloadscsv","allfiledownloads",backfillStartDate,backfillEndDate);
        }*/

        System.out.println("Partitions created successfully!");
    }

    private List<String> getAthenaResult(String year){
        String query = "select instance,min(year) as minyear, max(year) as maxyear, min(month) as minmonth, max(month) as maxmonth, min(day) as minday, max(day) as maxday from dev469filedownloadsrecords where year='" + year + "' group by instance ";
        QueryExecutionContext queryExecutionContext = new QueryExecutionContext().withDatabase("dev469firehoselogs"); // Replace with your database name

        // Create a ResultConfiguration
        ResultConfiguration resultConfiguration = new ResultConfiguration().withOutputLocation("s3://dev.log.sagebase.org/accessRecordtest/");

        // Create a StartQueryExecutionRequest
        StartQueryExecutionRequest startQueryExecutionRequest = new StartQueryExecutionRequest()
                .withQueryString(query)
                .withQueryExecutionContext(queryExecutionContext)
                .withResultConfiguration(resultConfiguration);

        // Start the query execution
        StartQueryExecutionResult startQueryExecutionResult = athena.startQueryExecution(startQueryExecutionRequest);

        // Get the query execution ID
        String queryExecutionId = startQueryExecutionResult.getQueryExecutionId();
        System.out.println("execution id is : "+ queryExecutionId);
        // Wait for the query to complete (optional)
        waitForQueryCompletion(athena, queryExecutionId);
        return getQueryResults(athena, queryExecutionId);
    }

    private static void waitForQueryCompletion(AmazonAthena athenaClient, String queryExecutionId) {
        GetQueryExecutionRequest getQueryExecutionRequest = new GetQueryExecutionRequest()
                .withQueryExecutionId(queryExecutionId);

        GetQueryExecutionResult queryExecution;
        QueryExecutionStatus status;

        do {
            queryExecution = athenaClient.getQueryExecution(getQueryExecutionRequest);
            status = queryExecution.getQueryExecution().getStatus();
            // Sleep for a few seconds before checking the status again
            try {
                System.out.println(status.getState());
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        } while (!(status.getState().equals("SUCCEEDED") || status.getState().equals("FAILED")));

        System.out.println("Query execution status: " + status.getState());
    }

    private static List<String> getQueryResults(AmazonAthena athenaClient, String queryExecutionId) {
        GetQueryResultsRequest getQueryResultsRequest = new GetQueryResultsRequest()
                .withQueryExecutionId(queryExecutionId);

        GetQueryResultsResult queryResultsResponse = athenaClient.getQueryResults(getQueryResultsRequest);


        ResultSetMetadata metadata = queryResultsResponse.getResultSet().getResultSetMetadata();

        for(int i = 0; i< metadata.getColumnInfo().size(); i++) {
            System.out.print(metadata.getColumnInfo().get(i)+" \t");
        }
        System.out.println();
        Map<String, List<String>> inputs = new HashMap<>();
        List<String> instances = new ArrayList<>();

        // Process and print the results
        for (Row row : queryResultsResponse.getResultSet().getRows()) {
            String instance = getColumnValue(row.getData().get(0));
            String minYear = getColumnValue(row.getData().get(1));
            String maxYear =  getColumnValue(row.getData().get(2));
            String minMonth = getColumnValue(row.getData().get(3));
            String maxMonth =  getColumnValue(row.getData().get(4));
            String minDay = getColumnValue(row.getData().get(5));
            String maxDay =  getColumnValue(row.getData().get(6));
            String startDate = String.join("-", minYear,minMonth,minDay);
            String endDate = String.join("-", maxYear,maxMonth,maxDay);
            inputs.put(instance, Arrays.asList(startDate, endDate));
            instances.add(instance);
        }
        System.out.println("print input list");
        for (String ins : instances) {
            System.out.println(ins);
        }
        return instances;
    }
    private static String getColumnValue(Datum datum) {
        if (datum != null && datum.getVarCharValue() != null) {
            return datum.getVarCharValue();
        }
        return "";
    }
    public void createGluePartitionForOldData(final String prefix, final String bucketName, final String databaseName) {
        final ListObjectsV2Request listObjectsV2Request = new ListObjectsV2Request().withPrefix(prefix).withBucketName(bucketName).withDelimiter("/");
        final ListObjectsV2Result s3ObjectResult = s3Client.listObjectsV2(listObjectsV2Request);
        if(s3ObjectResult == null || s3ObjectResult.getCommonPrefixes().size() == 0) {
            getBatchPartitionParametersAndCreateGluePartition(prefix, databaseName, bucketName);
            return;
        }
        for (String newPath : s3ObjectResult.getCommonPrefixes()) {
            if(checkToIterate(prefix, newPath)) {
                createGluePartitionForOldData(newPath, bucketName,databaseName);
            }
        }
    }

    public void getBatchPartitionParametersAndCreateGluePartition(String prefix,  String databaseName,  String bucketName) {
        int firstDelimiterIndex = prefix.indexOf("/");
        int midDelimiterIndex = prefix.indexOf("/", firstDelimiterIndex+1);
        final String releaseNumber = prefix.substring(0, firstDelimiterIndex);
        final String midPath = prefix.substring(firstDelimiterIndex+1, midDelimiterIndex);
        final String recordDate = prefix.substring(midDelimiterIndex+1, prefix.length()-1 );

        createBatchPartition(databaseName, tableToMidMap.get(midPath), releaseNumber, recordDate, midPath, "s3://"+bucketName);
    }

    public boolean checkToIterate(final String prefix, final String newPath) {
        if(prefix.length() == 0 && newPath.startsWith("000000")) return true;
        return newPath.contains("bulkfiledownloadresponse") || newPath.contains("filedownloadrecord");
    }
    private String getS3PartitionLocation(final String s3Localtion, final String releaseNumber, final String recordDate, final String midPath) {
        final String partitionLocation =  String.join("/", s3Localtion, releaseNumber, midPath, recordDate);
        System.out.println("Partition Location: "+partitionLocation);
        return partitionLocation;
    }

    private GetTableResult getCurrentSchema(final String databaseName, final String tableName) {
        final GetTableRequest getTableRequest = new GetTableRequest().withDatabaseName(databaseName).withName(tableName);
        return awsGlue.getTable(getTableRequest);
    }
    private StorageDescriptor createStorageDescriptor(final String databaseName, final String tableName,
                                                      final String releaseNumber, final String recordDate,
                                                      final String midPath, final String s3Location) {
        final GetTableResult getTableResult = getCurrentSchema(databaseName, tableName);
        final StorageDescriptor currentTableStorageDescriptor = getTableResult.getTable().getStorageDescriptor();
        return new StorageDescriptor()
                .withLocation(getS3PartitionLocation(s3Location,releaseNumber, recordDate, midPath))
                .withInputFormat(currentTableStorageDescriptor.getInputFormat())
                .withOutputFormat(currentTableStorageDescriptor.getOutputFormat())
                .withSerdeInfo(currentTableStorageDescriptor.getSerdeInfo());

    }

    private StorageDescriptor createStorageDescriptor1( String databaseName,  String tableName,
                                                       String year, String month,String day,  String s3Location) {
        final GetTableResult getTableResult = getCurrentSchema(databaseName, tableName);
        final StorageDescriptor currentTableStorageDescriptor = getTableResult.getTable().getStorageDescriptor();
        final String partitionLocation =  String.join("/", s3Location, year, month, day);
        return new StorageDescriptor()
                .withLocation(partitionLocation)
                .withInputFormat(currentTableStorageDescriptor.getInputFormat())
                .withOutputFormat(currentTableStorageDescriptor.getOutputFormat())
                .withSerdeInfo(currentTableStorageDescriptor.getSerdeInfo());

    }

    private void createBatchPartition(final String databaseName, final String tableName, final String releaseNumber,
                                      final String recordDate, final String midPath, final String s3Location) {
        final StorageDescriptor storageDescriptor = createStorageDescriptor(databaseName, tableName, releaseNumber,
                                                                            recordDate, midPath, s3Location);
        final PartitionInput partitionInput = new PartitionInput()
                                                .withValues(releaseNumber,recordDate)
                                                .withStorageDescriptor(storageDescriptor);
        final BatchCreatePartitionRequest batchCreatePartitionRequest = new BatchCreatePartitionRequest()
                                                                            .withDatabaseName(databaseName)
                                                                            .withTableName(tableName)
                                                                            .withPartitionInputList(partitionInput);
        awsGlue.batchCreatePartition(batchCreatePartitionRequest);
        System.out.println("Partition created successfully");
    }

    private void startAWSGLueJob(final String jobName, final String releaseNumber, final String stack, final String dataBaseName,
                                 final String sourceBulkTableName, final String sourceFileTableName,
                                 final String destinationTableName, final String startDate, final String endDate) {
        final Map<String, String> argumentMap = ImmutableMap.ofEntries(
                entry("--DESTINATION_DATABASE_NAME", dataBaseName),
                entry("--DESTINATION_TABLE_NAME",destinationTableName),
                entry("--SOURCE_DATABASE_NAME",dataBaseName),
                entry("--SOURCE_BULK_TABLE_NAME",sourceBulkTableName),
                entry("--SOURCE_FILE_TABLE_NAME",sourceFileTableName),
                entry("--START_DATE",startDate),
                entry("--END_DATE",endDate),
                entry("--RELEASE_NUMBER",releaseNumber),
                entry("--STACK",stack));
        final StartJobRunRequest startJobRunRequest = new StartJobRunRequest().withArguments(argumentMap).withJobName(jobName);
        System.out.println("Starting Glue Job");
        awsGlue.startJobRun(startJobRunRequest);
    }
    String copyArtifactFromGithub(String bucket) {
        String githubRepo = "Synapse-ETL-Jobs";
        String version = "1.30.0";
        String githubUrl = String.format(GITHUB_URL_TPL, githubRepo, version);
        String scriptPath = String.format(SCRIPT_PATH_TPL, githubRepo, version);
        String s3ScriptsPath = S3_BACKFILL_KEY_PATH_TPL;

        File zipFile = downloader.downloadFile(githubUrl);

        try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(zipFile.toPath()))) {
            ZipEntry entry = null;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().contains(scriptPath)) {
                    String scriptFile = entry.getName();
                    String s3Key = s3ScriptsPath + scriptFile.replace(scriptPath, "");
                    logger.info("Uploading " + scriptFile + " to " + s3Key);
                    // Uses a stream with close disabled so that the s3 sdk does not close it for us
                    s3Client.putObject(bucket, s3Key, ReleasableInputStream.wrap(zipInputStream).disableClose(), null);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            zipFile.delete();
        }
        return s3ScriptsPath;
    }
}

class CustomRow {
    private String instance;
    private String minyear;
    private String maxyear;
    private String minmonth;
    private String maxmonth;
    private String minday;
    private String maxday;

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getMinyear() {
        return minyear;
    }

    public void setMinyear(String minyear) {
        this.minyear = minyear;
    }

    public String getMaxyear() {
        return maxyear;
    }

    public void setMaxyear(String maxyear) {
        this.maxyear = maxyear;
    }

    public String getMinmonth() {
        return minmonth;
    }

    public void setMinmonth(String minmonth) {
        this.minmonth = minmonth;
    }

    public String getMaxmonth() {
        return maxmonth;
    }

    public void setMaxmonth(String maxmonth) {
        this.maxmonth = maxmonth;
    }

    public String getMinday() {
        return minday;
    }

    public void setMinday(String minday) {
        this.minday = minday;
    }

    public String getMaxday() {
        return maxday;
    }

    public void setMaxday(String maxday) {
        this.maxday = maxday;
    }

    @Override
    public String toString() {
        return "CustomRow{" +
                "instance='" + instance + '\'' +
                ", minyear='" + minyear + '\'' +
                ", maxyear='" + maxyear + '\'' +
                ", minmonth='" + minmonth + '\'' +
                ", maxmonth='" + maxmonth + '\'' +
                ", minday='" + minday + '\'' +
                ", maxday='" + maxday + '\'' +
                '}';
    }
}