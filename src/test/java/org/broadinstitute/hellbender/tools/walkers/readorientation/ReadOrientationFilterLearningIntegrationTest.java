package org.broadinstitute.hellbender.tools.walkers.readorientation;

import com.google.common.collect.ImmutableMap;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.util.CloserUtil;
import htsjdk.samtools.util.Histogram;
import htsjdk.samtools.util.IOUtil;
import org.apache.spark.sql.catalyst.expressions.aggregate.Collect;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.Main;
import org.broadinstitute.hellbender.tools.walkers.mutect.FilterMutectCalls;
import org.broadinstitute.hellbender.utils.MathUtils;
import org.broadinstitute.hellbender.utils.genotyper.*;
import org.broadinstitute.hellbender.utils.read.ArtificialReadUtils;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadUtils;
import org.broadinstitute.hellbender.utils.read.SAMFileGATKReadWriter;
import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.internal.junit.ArrayAsserts;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Created by tsato on 8/1/17.
 */
public class ReadOrientationFilterLearningIntegrationTest extends CommandLineProgramTest {
    /**
     * Test the tool on a real bam to make sure that it does not crash
     */
    @Test
    public void testOnRealBam() throws IOException {
        final File refMetrics = createTempFile("ref", ".table");
        final File altTable = createTempFile("alt", ".table");

        new Main().instanceMain(makeCommandLineArgs(
                Arrays.asList(
                        "-R", v37_chr17_1Mb_Reference,
                        "-I", NA12878_chr17_1k_BAM,
                        "-" + CollectDataForReadOrientationFilter.ALT_DATA_TABLE_SHORT_NAME, altTable.getAbsolutePath(),
                        "-" + CollectDataForReadOrientationFilter.REF_SITE_METRICS_SHORT_NAME, refMetrics.getAbsolutePath()),
                CollectDataForReadOrientationFilter.class.getSimpleName()));

        int lineCount = (int) Files.lines(Paths.get(refMetrics.getAbsolutePath())).filter(l -> l.matches("^[0-9].+")).count();

        // Ensure that we print every bin, even when the count is 0
        Assert.assertEquals(lineCount, CollectDataForReadOrientationFilter.MAX_REF_DEPTH);

        // Run LearnHyperparameter
        final File hyperparameters = createTempFile("hyperparameters", ".tsv");
        new Main().instanceMain(makeCommandLineArgs(
                Arrays.asList(
                        "-alt-table", altTable.getAbsolutePath(),
                        "-ref-table", refMetrics.getAbsolutePath(),
                        "-O", hyperparameters.getAbsolutePath()),
                LearnHyperparameters.class.getSimpleName()));

        final List<Hyperparameters> hyperparametersList = Hyperparameters.readHyperparameters(hyperparameters);
        final Hyperparameters hypsForACT = Hyperparameters.searchByContext(hyperparametersList, "ACT").get();
        final Hyperparameters hypsForAGT = Hyperparameters.searchByContext(hyperparametersList, "AGT").get();

        ArrayAsserts.assertArrayEquals(hypsForACT.getPi(), hypsForAGT.getPi(), 1e-5);

    }
}