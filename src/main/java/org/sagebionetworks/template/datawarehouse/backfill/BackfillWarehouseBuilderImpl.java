package org.sagebionetworks.template.datawarehouse.backfill;

import com.amazonaws.internal.ReleasableInputStream;
import com.amazonaws.services.s3.AmazonS3;
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
import java.util.List;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.sagebionetworks.template.Constants.CAPABILITY_NAMED_IAM;
import static org.sagebionetworks.template.Constants.EXCEPTION_THROWER;
import static org.sagebionetworks.template.Constants.GLUE_DATABASE_NAME;
import static org.sagebionetworks.template.Constants.JSON_INDENT;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_DATAWAREHOUSE_GLUE_DATABASE_NAME;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_STACK;
import static org.sagebionetworks.template.Constants.STACK;

public class BackfillWarehouseBuilderImpl implements BackfillWarehouseBuilder {
    private static final String S3_GLUE_BUCKET = "aws-glue.sagebase.org";
    public static final String TEMPLATE_ETL_GLUE_JOB_RESOURCES = "templates/datewarehouse/backfill-etl-jobs-template.json.vpt";
    private static final String S3_BACKFILL_KEY_PATH_TPL = "scripts/backfill/";
    private static final String GITHUB_URL_TPL = "https://codeload.github.com/Sage-Bionetworks/%s/zip/refs/tags/v%s";
    private static final String SCRIPT_PATH_TPL = "%s-%s/src/scripts/backfill_jobs/";
    private static final String GS_EXPLODE_SCRIPT = "s3://aws-glue-studio-transforms-510798373988-prod-us-east-1/gs_explode.py";
    private static final String GS_COMMON_SCRIPT = "s3://aws-glue-studio-transforms-510798373988-prod-us-east-1/gs_common.py";

    private EtlJobConfig etlJobConfig;
    private ArtifactDownload downloader;
    private Configuration config;
    private Logger logger;
    private VelocityEngine velocityEngine;

    private AmazonS3 s3Client;
    private CloudFormationClient cloudFormationClient;
    private StackTagsProvider tagsProvider;

    @Inject
    public BackfillWarehouseBuilderImpl(CloudFormationClient cloudFormationClient, VelocityEngine velocityEngine,
                                    Configuration config, LoggerFactory loggerFactory,
                                    StackTagsProvider tagsProvider, EtlJobConfig etlJobConfig, ArtifactDownload downloader, AmazonS3 s3Client) {
        this.cloudFormationClient = cloudFormationClient;
        this.velocityEngine = velocityEngine;
        this.config = config;
        this.logger = loggerFactory.getLogger(DataWarehouseBuilderImpl.class);
        this.tagsProvider = tagsProvider;
        this.etlJobConfig = etlJobConfig;
        this.downloader = downloader;
        this.s3Client = s3Client;
    }

    // copy scripts to s3
    // create a cloudfromation to create glue job and tables
    // create partition on glue csv table
    //run athena query to get year month day and instance
    //run old datawarehouse job in a loop
    // run kinesis job in a loop
    // run step function or query to dedupe
    // run another job
    @Override
    public void buildAndDeploy() {
        String databaseName = config.getProperty(PROPERTY_KEY_DATAWAREHOUSE_GLUE_DATABASE_NAME);
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
        String utilscript = scriptLocationPrefix + "backfill_utils.py";
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
        // create or update the template
        String stackName = new StringJoiner("-").add(stack).add(databaseName).add("backfill-etl-jobs").toString();
        this.cloudFormationClient.createOrUpdateStack(new CreateOrUpdateStackRequest().withStackName(stackName)
                .withTemplateBody(resultJSON).withTags(tagsProvider.getStackTags())
                .withCapabilities(CAPABILITY_NAMED_IAM));
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
