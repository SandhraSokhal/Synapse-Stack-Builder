package org.sagebionetworks.template.repo;

import java.io.StringWriter;

import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.json.JSONObject;
import org.sagebionetworks.template.CloudFormationClient;
import org.sagebionetworks.template.config.Configuration;
import org.sagebionetworks.template.Constants;
import org.sagebionetworks.template.CreateOrUpdateStackRequest;
import org.sagebionetworks.template.LoggerFactory;
import org.sagebionetworks.template.repo.beanstalk.SecretBuilder;

import com.amazonaws.services.cloudformation.model.Parameter;
import com.google.inject.Inject;

import static org.sagebionetworks.template.Constants.*;

public class IdGeneratorBuilderImpl implements IdGeneratorBuilder {

	CloudFormationClient cloudFormationClient;
	VelocityEngine velocityEngine;
	Configuration config;
	Logger logger;
	SecretBuilder secretBuilder;

	@Inject
	public IdGeneratorBuilderImpl(CloudFormationClient cloudFormationClient, VelocityEngine velocityEngine,
			Configuration config, LoggerFactory loggerFactory, SecretBuilder secretBuilder) {
		super();
		this.cloudFormationClient = cloudFormationClient;
		this.velocityEngine = velocityEngine;
		this.config = config;
		this.logger = loggerFactory.getLogger(IdGeneratorBuilderImpl.class);
		this.secretBuilder = secretBuilder;
	}

	@Override
	public void buildAndDeploy() {
		VelocityContext context = new VelocityContext();
		String color = config.getProperty(PROPERTY_KEY_VPC_SUBNET_COLOR);
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		String hostedZoneId = config.getProperty(PROPERTY_KEY_ID_GENERATOR_HOSTED_ZONE_ID);
		String databaseIdentifier = stack+"-id-generator-db-2-"+color.toLowerCase();
		context.put(STACK, stack);
		context.put(GLOBAL_RESOURCES_EXPORT_PREFIX, Constants.createGlobalResourcesExportPrefix(stack));
		context.put(VPC_EXPORT_PREFIX, Constants.createVpcExportPrefix(stack));
		context.put(VPC_SUBNET_COLOR, color);
		context.put(DATABASE_IDENTIFIER, databaseIdentifier);
		context.put(HOSTED_ZONE, hostedZoneId);

		Parameter parameter = new Parameter();
		parameter.withParameterKey(Constants.PARAMETER_MYSQL_PASSWORD);
		String password = secretBuilder.getIdGeneratorPassword();
		parameter.withParameterValue(password);

		// Merge the context with the template
		Template template = this.velocityEngine.getTemplate(TEMPLATE_ID_GENERATOR);
		StringWriter stringWriter = new StringWriter();
		template.merge(context, stringWriter);
		// Parse the resulting template
		String resultJSON = stringWriter.toString();
		JSONObject templateJson = new JSONObject(resultJSON);
		// Format the JSON
		resultJSON = templateJson.toString(JSON_INDENT);
		System.out.println(resultJSON);
		String stackName = stack + "-id-generator-2-"+color.toLowerCase();
		this.logger.info("Template for stack: " + stackName);
		this.logger.info(resultJSON);
		// create or update the template
		this.cloudFormationClient.createOrUpdateStack(new CreateOrUpdateStackRequest().withStackName(stackName)
				.withTemplateBody(resultJSON).withParameters(parameter));

	}

}
