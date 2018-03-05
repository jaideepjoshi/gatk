package org.broadinstitute.hellbender.tools.spark.sv.evidence;

import jdk.nashorn.internal.runtime.regexp.joni.exception.ValueException;
import org.broadinstitute.hellbender.exceptions.GATKException;
import org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryArgumentCollection;

import htsjdk.samtools.SAMFileHeader;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.broadcast.Broadcast;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.barclay.argparser.ExperimentalFeature;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.barclay.help.DocumentedFeature;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.StructuralVariantDiscoveryProgramGroup;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.engine.spark.SparkCommandLineProgram;
import org.broadinstitute.hellbender.tools.spark.sv.StructuralVariationDiscoveryArgumentCollection;
import org.broadinstitute.hellbender.tools.spark.utils.FlatMapGluer;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.SAMRecordToGATKReadAdapter;

import static org.broadinstitute.hellbender.tools.spark.sv.evidence.FindBreakpointEvidenceSpark.buildMetadata;


import biz.k11i.xgboost.Predictor;
import biz.k11i.xgboost.util.FVec;
import biz.k11i.xgboost.gbm.GBTree;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.io.IOUtils;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;
import org.dmg.pmml.Value;
import org.json.JSONArray;
import org.json.JSONObject;
import com.google.gson.Gson;
import com.fasterxml.jackson.databind.node.*;

/**
 * (Internal) Extracts evidence of structural variations from reads
 *
 * <p>This tool is used in development and should not be of interest to most researchers.  It repackages the first
 * two steps of the structural variation workflow as a separate tool for the convenience of developers.</p>
 * <p>This tool examines a SAM/BAM/CRAM for reads, or groups of reads, that demonstrate evidence of a structural
 * variation in the vicinity.  It records this evidence as a group of text files in a specified output directory
 * on Spark's HDFS file system.</p>
 *
 * <h3>Inputs</h3>
 * <ul>
 *     <li>A file of paired-end, aligned and coordinate-sorted reads.</li>
 * </ul>
 *
 * <h3>Output</h3>
 * <ul>
 *     <li>A directory of text files describing the evidence for structural variation discovered.</li>
 * </ul>
 *
 * <h3>Usage example</h3>
 * <pre>
 *   gatk ExtractSVEvidenceSpark \
 *     -I input_reads.bam \
 *     -O hdfs://my_cluster-m:8020/output_directory
 *     --aligner-index-image ignored --kmers-to-ignore ignored
 * </pre>
 * <p>This tool can be run without explicitly specifying Spark options. That is to say, the given example command
 * without Spark options will run locally. See
 * <a href ="https://software.broadinstitute.org/gatk/documentation/article?id=10060">Tutorial#10060</a>
 * for an example of how to set up and run a Spark tool on a cloud Spark cluster.</p>
 */
@DocumentedFeature
@ExperimentalFeature
@CommandLineProgramProperties(
        oneLineSummary = "(Internal) Extracts evidence of structural variations from reads",
        summary =
        "This tool is used in development and should not be of interest to most researchers.  It consumes the evidence" +
        " data dumped by ExtractSVEvidenceSpark and in conjuction with truth data from addition VCFs, trains a" +
        " classifier to validate BreakpointEvidence. Validated BreakpointEvidence will be grouped into connected" +
        " components (SVIntervals) which will trigger assembly of neighboring read pairs.",
        programGroup = StructuralVariantDiscoveryProgramGroup.class)
public final class TrainSVEvidenceValidator extends SparkCommandLineProgram {
    private static final long serialVersionUID = 1L;

    @ArgumentCollection
    private final StructuralVariationDiscoveryArgumentCollection.TrainSVEvidenceValidatorArgumentCollection params =
            new StructuralVariationDiscoveryArgumentCollection.TrainSVEvidenceValidatorArgumentCollection();


    @Argument(doc = "HDFS path for output", shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    private String outputDir;

    @Argument(doc = "Path to classifier model file", fullName = "classifier-model-file")
    private String classifierModelFile;

    @Argument(doc = "Path to test data to evaluate with classifier", fullName = "test-data-json-file")
    private String testDataJsonFile;

    /**
     * Runs the pipeline.
     */
    @Override
    protected void runPipeline(final JavaSparkContext ctx) {
        // write this!
        try {
            // Load model and create Predictor
            Predictor predictor = new Predictor(new FileInputStream(classifierModelFile));

            JsonNode testDataNode = new ObjectMapper().readTree(new File(testDataJsonFile));
            final FVec[] testFeatures = getFVecArrayFromJsonNode(testDataNode.get("X").get("data"));
        } catch(final Exception err) {
            throw new GATKException("Doh", err);
        }
    }

    public static FVec[] getFVecArrayFromJsonNode(final JsonNode matrixNode) {
        if(!matrixNode.has("__class__")) {
            throw new IllegalArgumentException("JSON node does not store python matrix data");
        }
        String matrixClass = matrixNode.get("__class__").asText();
        switch(matrixClass) {
            case "pandas.DataFrame":
                return getFVecArrayFromPandasJsonNode(matrixNode.get("data"));
            case "numpy.array":
                return getFVecArrayFromNumpyJsonNode(matrixNode.get("data"));
            default:
                throw new IllegalArgumentException("JSON node has __class__ = " + matrixClass
                        + "which is not a supported matrix type");
        }
    }

    public static FVec[] getFVecArrayFromNumpyJsonNode(final JsonNode dataNode) {
        if(!dataNode.isArray()) {
            throw new IllegalArgumentException("dataNode does not encode a valid numpy array");
        }
        final int numRows = dataNode.size();
        final FVecDoubleArraySimpleImpl[] matrix = new FVecDoubleArraySimpleImpl[numRows];
        if (numRows == 0) {
            return matrix;
        }
        matrix[0] = new FVecDoubleArraySimpleImpl(dataNode.get(0));
        final int numColumns = matrix[0].length();
        for (int row = 1; row < numRows; ++row) {
            matrix[row] = new FVecDoubleArraySimpleImpl(dataNode.get(row));
            final int numRowColumns = matrix[row].length();
            if (numRowColumns != numColumns) {
                throw new IllegalArgumentException("Rows in JSONArray have different lengths.");
            }
        }
        return matrix;
    }

    public static FVec[] getFVecArrayFromPandasJsonNode(final JsonNode dataNode) {
        if(!dataNode.isObject()) {
            throw new IllegalArgumentException("dataNode does not encode a valid pandas DataFrame");
        }
        final int numColumns = dataNode.size();
        if(numColumns == 0) {
            return new FVec[0];
        }

        final String firstColumnName = dataNode.fieldNames().next();
        final int numRows = dataNode.get(firstColumnName).get("values").size();
        final FVecDoubleArraySimpleImpl[] matrix = new FVecDoubleArraySimpleImpl[numRows];
        if (numRows == 0) {
            return matrix;
        }
        int row_index = 0;
        for(final Map.Entry<String, JsonNode> columnEntry: dataNode.fields()) {
            final FVecDoubleArraySimpleImpl fVec = matrix[row_index];
            int col_index = 0;
            for(final JsonNode valueNode: columnEntry.getValue().get("values")) {
                fVec.set_value(col_index, valueNode.getDoubleValue());
                ++col_index;
            }
            ++row_index;
        }
        return matrix;
    }

    public static FVec[] getFVecArrayFromJSONArray(final JSONArray matrixArray) {
        final int numRows = matrixArray.length();
        final FVecDoubleArraySimpleImpl[] matrix = new FVecDoubleArraySimpleImpl[numRows];
        if (numRows == 0) {
            return matrix;
        }
        matrix[0] = new FVecDoubleArraySimpleImpl(matrixArray.getJSONArray(0));
        final int numColumns = matrix[0].length();
        for (int row = 1; row < numRows; ++row) {
            matrix[row] = new FVecDoubleArraySimpleImpl(matrixArray.getJSONArray(row));
            final int numRowColumns = matrix[row].length();
            if (numRowColumns != numColumns) {
                throw new IllegalArgumentException("Rows in JSONArray have different lengths.");
            }
        }
        return matrix;
    }

    static class FVecDoubleArraySimpleImpl implements FVec {
        private final double[] values;

        FVecDoubleArraySimpleImpl(final int numFeatures) {this.values = new double[numFeatures];}

        FVecDoubleArraySimpleImpl(final double[] values) {this.values = values;}

        FVecDoubleArraySimpleImpl(final JSONArray jsonArray) {
            final int numFeatures = jsonArray.length();
            this.values = new double[numFeatures];
            for(int col = 0; col < numFeatures; ++col) {
                this.values[col] = jsonArray.getDouble(col);
            }
        }

        FVecDoubleArraySimpleImpl(final JsonNode arrayNode) {
            if(!arrayNode.isArray()) {
                throw new IllegalArgumentException("JsonNode does not contain an Array");
            }
            final int numFeatures = arrayNode.size();
            this.values = new double[numFeatures];
            int ind = 0;
            for(final JsonNode element: arrayNode) {
                values[ind] = element.getDoubleValue();
                ++ind;
            }
        }

        @Override
        public double fvalue(final int index){return values[index];}

        public int length() {return this.values.length;}

        public void set_value(final int index, final double value){values[index] = value;}
    }

}
