package org.broadinstitute.hellbender.tools.walkers.readorientation;

import htsjdk.samtools.util.SequenceUtil;
import org.broadinstitute.hellbender.utils.Nucleotide;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Created by tsato on 3/14/18.
 */
public class ReadOrientationFilterConstants {
    public static final int REF_CONTEXT_PADDING_ON_EACH_SIDE = 1;

    public static final int MIDDLE_INDEX = REF_CONTEXT_PADDING_ON_EACH_SIDE;

    public static final int REFERENCE_CONTEXT_SIZE = 2 * REF_CONTEXT_PADDING_ON_EACH_SIDE + 1; // aka 3

    public static final List<Nucleotide> REGULAR_BASES = Arrays.asList(Nucleotide.A, Nucleotide.C, Nucleotide.G, Nucleotide.T);

    // the list of all possible kmers, where k = REFERENCE_CONTEXT_SIZE
    static final List<String> ALL_KMERS = SequenceUtil.generateAllKmers(REFERENCE_CONTEXT_SIZE).stream()
            .map(String::new).collect(Collectors.toList());

    static final List<String> ALL_KMERS_MODULO_REVERSE_COMPLEMENT = ALL_KMERS.stream()
            .map(context -> new TreeSet<>(Arrays.asList(context, SequenceUtil.reverseComplement(context))))
            .distinct()
            .map(s -> s.first().compareTo(s.last()) < 0 ? s.first() : s.last())
            .collect(Collectors.toList());

}
