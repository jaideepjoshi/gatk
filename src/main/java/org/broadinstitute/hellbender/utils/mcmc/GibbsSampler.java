package org.broadinstitute.hellbender.utils.mcmc;


import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.RandomGeneratorFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.param.ParamUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Implements Gibbs sampling of a multivariate probability density function.
 * See GibbsSamplerSingleGaussianUnitTest and GibbsSamplerCopyRatioUnitTest for examples of use.
 *
 * @author Samuel Lee &lt;slee@broadinstitute.org&gt;
 */
public final class GibbsSampler<V extends Enum<V> & ParameterEnum, S extends ParameterizedState<V>, T extends DataCollection> {
    private static final int RANDOM_SEED = 42;
    private static final RandomGenerator rng =
            RandomGeneratorFactory.createRandomGenerator(new Random(RANDOM_SEED));

    private static final Logger logger = LogManager.getLogger(GibbsSampler.class);
    private static final int NUMBER_OF_SAMPLES_PER_LOG_ENTRY = 25;

    private final int numSamples;
    private int numSamplesPerLogEntry;

    private final ParameterizedModel<V, S, T> model;

    private final List<S> samples;

    private boolean isMCMCRunComplete = false;

    /**
     * Constructs a GibbsSampler given the total number of samples (including burn-in) and a {@link ParameterizedModel}.
     * The {@link ParameterizedState} held by the model is used to initialize the Monte Carlo Markov Chain and is taken
     * to be the first sample.  Number of samples per log entry will be set to the default.
     * @param numSamples    total number of samples; must be positive
     * @param model         {@link ParameterizedModel} to be sampled
     */
    public GibbsSampler(final int numSamples, final ParameterizedModel<V, S, T> model) {
        ParamUtils.isPositive(numSamples, "Number of samples must be positive.");
        Utils.validateArg(model.getUpdateMethod() == ParameterizedModel.UpdateMethod.GIBBS, "ParameterizedModel must be constructed to update using Gibbs sampling.");
        this.numSamples = numSamples;
        this.model = model;
        numSamplesPerLogEntry = NUMBER_OF_SAMPLES_PER_LOG_ENTRY;
        samples = new ArrayList<>(numSamples);
        samples.add(model.state());
    }

    /**
     * Changes the number of samples per log entry.
     * @param numSamplesPerLogEntry number of samples per log entry; must be positive
     */
    public void setNumSamplesPerLogEntry(final int numSamplesPerLogEntry) {
        ParamUtils.isPositive(numSamplesPerLogEntry, "Number of samples per log entry must be positive.");
        this.numSamplesPerLogEntry = numSamplesPerLogEntry;
    }

    /**
     * Runs the Monte Carlo Markov Chain, using the state of the model provided in the constructor to initialize.
     * Progress is logged according to {@code numSamplesPerLogEntry}.
     */
    public void runMCMC() {
        rng.setSeed(RANDOM_SEED);
        logger.info("Starting MCMC sampling.");
        for (int sample = 1; sample < numSamples; sample++) {
            if (sample % numSamplesPerLogEntry == 0) {
                logger.info(sample + " of " + numSamples + " samples generated.");
            }
            model.update(rng);
            samples.add(model.state());
        }
        logger.info(numSamples + " of " + numSamples + " samples generated.");
        logger.info("MCMC sampling complete.");
        isMCMCRunComplete = true;
    }

    /**
     * Returns a list of samples for a specified model parameter, discarding the first {@code numBurnIn} samples.
     * @param parameterName         name of parameter
     * @param parameterValueClass   class of parameter value
     * @param numBurnIn             number of burn-in samples to discard from beginning of chain
     * @param <U>                   type of parameter value
     * @return                      List of parameter samples
     */
    public <U> List<U> getSamples(final V parameterName, final Class<U> parameterValueClass, final int numBurnIn) {
        ParamUtils.isPositiveOrZero(numBurnIn, "Number of burn-in samples must be non-negative.");
        Utils.validateArg(numBurnIn < numSamples, "Number of samples must be greater than number of burn-in samples.");
        if (!isMCMCRunComplete) {
            runMCMC();
        }
        return samples.stream().map(s -> s.get(parameterName, parameterValueClass)).collect(Collectors.toList())
                .subList(numBurnIn, numSamples);
    }
}