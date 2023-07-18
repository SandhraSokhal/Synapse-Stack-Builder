package org.sagebionetworks.template.repo.kinesis.firehose;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class GlueTableDescriptor {

	private String name;
	private List<GlueColumn> columns = new ArrayList<>();
	private List<GlueColumn> partitionKeys = new ArrayList<>();
	
	// Additional custom parameters for the glue table
	private Map<String, String> parameters = null;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<GlueColumn> getColumns() {
		return columns;
	}

	public void setColumns(List<GlueColumn> columns) {
		this.columns = columns;
	}

	public List<GlueColumn> getPartitionKeys() {
		return partitionKeys;
	}

	public void setPartitionKeys(List<GlueColumn> partitionKeys) {
		this.partitionKeys = partitionKeys;
	}

	public Map<String, String> getParameters() {
		return parameters;
	}
	
	public void setParameters(Map<String, String> parameters) {
		this.parameters = parameters;
	}

	@Override
	public int hashCode() {
		return Objects.hash(columns, name, parameters, partitionKeys);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		GlueTableDescriptor other = (GlueTableDescriptor) obj;
		return Objects.equals(columns, other.columns) && Objects.equals(name, other.name) && Objects.equals(parameters, other.parameters)
				&& Objects.equals(partitionKeys, other.partitionKeys);
	}

	

}
