package edu.umass.cs.surveyman.qc;

import edu.umass.cs.surveyman.SurveyMan;
import edu.umass.cs.surveyman.analyses.*;
import edu.umass.cs.surveyman.output.*;
import edu.umass.cs.surveyman.survey.*;
import edu.umass.cs.surveyman.survey.exceptions.SurveyException;
import org.apache.commons.math3.linear.BlockRealMatrix;
import org.apache.commons.math3.linear.EigenDecomposition;
import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.ml.clustering.CentroidCluster;
import org.apache.commons.math3.ml.clustering.Clusterable;
import org.apache.commons.math3.ml.clustering.KMeansPlusPlusClusterer;
import org.apache.commons.math3.stat.inference.MannWhitneyUTest;

import java.util.*;

public class QCMetrics {

    public static final Random rng = new Random();
    public static int bootstrapIterations = 2000;
    private static double log2(double p) {
        if (p == 0)
            return 0.0;
        return Math.log(p) / Math.log(2.0);
    }

    /**
     * Takes in a list of Blocks; returns a list of lists of Blocks representing all possible paths through the survey.
     * See @etosch's blog post for more detail.
     * @param blockList A list of blocks we would like to traverse.
     * @return A list of lists of blocks, giving all possible traversals through the original input.
     */
    public static List<List<Block>> getDag(
            List<Block> blockList)
    {
        Collections.sort(blockList);
        if (blockList.isEmpty()) {
            // return a singleton list of the empty list
            List<List<Block>> newSingletonList = new ArrayList<>();
            newSingletonList.add(new ArrayList<Block>());
            return newSingletonList;
        } else {
            Block thisBlock = blockList.get(0);
            if (thisBlock.hasBranchQuestion()) {
                Set<Block> dests = thisBlock.getBranchDestinations();
                List<List<Block>> blists = new ArrayList<>();
                for (Block b : dests) {
                    // for each destination, find the sublist of the blocklist starting with the destination
                    int index = blockList.indexOf(b);
                    if (index > -1) {
                        List<List<Block>> dags = getDag(blockList.subList(index, blockList.size()));
                        for (List<Block> dag : dags) {
                            dag.add(thisBlock);
                        }
                        blists.addAll(dags);
                    }
                }
                return blists;
            } else {
                List<List<Block>> subDag = getDag(blockList.subList(1, blockList.size()));
                for (List<Block> blist : subDag) {
                    blist.add(thisBlock);
                }
                return subDag;
            }
        }
    }

    /**
     * Returns paths through **blocks** in the survey. Top level randomized blocks are all listed last
     * @param s The survey whose paths we want to enumerate
     * @return A List of all paths through the survey. A path is represented by a List. There may be duplicate paths,
     * so if you need distinct paths, you will need to filter for uniqueness.
     */
    protected static List<Set<Block>> getPaths(Survey s)
    {
        List<Set<Block>> retval = new ArrayList<>();
        Map<Boolean, List<Block>> partitionedBlocks = Interpreter.partitionBlocks(s);
        List<Block> topLevelRandomizableBlocks = partitionedBlocks.get(true);
        List<Block> nonrandomizableBlocks = partitionedBlocks.get(false);
        Collections.sort(nonrandomizableBlocks);
        List<List<Block>> dag = getDag(nonrandomizableBlocks);
        SurveyMan.LOGGER.info("Computing paths for survey having DAG with "+dag.size()+" paths through fixed blocks.");
        if (dag.size() == 1 && dag.get(0).isEmpty()) {
            retval.add(new HashSet<>(topLevelRandomizableBlocks));
            return retval;
        }
        for (List<Block> blist : dag) {
            if (blist.isEmpty())
                continue;
            blist.addAll(topLevelRandomizableBlocks);
            retval.add(new HashSet<>(blist));
        }
        if (retval.size() > 1)
            SurveyMan.LOGGER.info(String.format("Computed %d paths through the survey.", retval.size()));
        return retval;
    }

    /**
     * Returns the set of enclosing blocks for this survey response.
     * @param r A single survey responses
     * @return The blocks the respondent has traversed in order to produce this response.
     */
    private static Set<Block> getPath(SurveyResponse r)
    {
        Set<Block> retval = new HashSet<>();
        for (IQuestionResponse questionResponse : r.getNonCustomResponses()) {
            Question q = questionResponse.getQuestion();
            retval.add(q.block.getFarthestContainingBlock());
        }
        return retval;
    }

    /**
     * Returns the counts for each path; see @etosch's blog post on the calculation.
     * @param paths The list of list of blocks through the survey; can be obtained with getPaths or getDag
     * @param responses The list of actual or simulated responses to the survey
     * @return A map from path to the frequency the path is observed.
     */
    protected static Map<Set<Block>, List<SurveyResponse>> makeFrequenciesForPaths(
            List<Set<Block>> paths,
            List<? extends SurveyResponse> responses)
    {
        Map<Set<Block>, List<SurveyResponse>> retval = new HashMap<>();
        // initialize the map
        for (Set<Block> path : paths)
            retval.put(path, new ArrayList<SurveyResponse>());
        for (SurveyResponse r : responses) {
            Set<Block> pathTraversed = getPath(r);
            boolean pathFound = false;
            for (Set<Block> path : retval.keySet()) {
                if (path.containsAll(pathTraversed)){
                    retval.get(path).add(r);
                    pathFound = true;
                    break;
                }
            }
            assert pathFound : "Path survey respondent took does not match any known paths through the survey.";
        }
        return retval;
    }

    protected static List<Question> removeFreetext(
            List<Question> questionList)
    {
        List<Question> questions = new ArrayList<>();
        for (Question q : questionList) {
            // freetext will be null if we've designed the survey programmatically and have
            // not added any questions (i.e. if it's an instructional question).
            // TODO(etosch) : subclass Question to make an Instructional Question.
            if (q.freetext == null)
                q.freetext = false;
            if (!q.freetext)
                questions.add(q);
        }
        return questions;
    }


    /**
     * Returns equivalent answer options (a list of survey.SurveyDatum)
     * @param q The question whose variants we want. If there are no variants, then a set of just this question is
     *          returned.
     * @param c The answer the respondent provided for this question.
     * @return A list of the equivalent answers.
     */
    protected static List<SurveyDatum> getEquivalentAnswerVariants(
            Question q,
            SurveyDatum c)
    {

        List<SurveyDatum> retval = new ArrayList<>();
        List<Question> variants = q.getVariants();
        int offset = q.getSourceRow() - c.getSourceRow();
        for (Question variant : variants) {
            for (SurveyDatum thisC : variant.options.values()) {
                int thisOffset = variant.getSourceRow() - thisC.getSourceRow();
                if (thisOffset == offset)
                    retval.add(thisC);
            }
        }
        //SurveyMan.LOGGER.debug("Variant set size: " + retval.size());
        return retval;
    }

    /**
     * Calculates the empirical entropy for this survey, given a set of responses.
     * @param survey The survey these respondents answered.
     * @param responses The list of actual or simulated responses to the survey.
     * @return The caluclated base-2 entropy.
     */
    public static double surveyEntropy(Survey survey,
                                       List<? extends SurveyResponse> responses)
    {
        List<Set<Block>> paths = getPaths(survey);
        Map<Set<Block>, List<SurveyResponse>> pathMap = makeFrequenciesForPaths(paths, responses);
        int totalResponses = responses.size();
        assert totalResponses > 1 : "surveyEntropy is meaningless for fewer than 1 response.";
        double retval = 0.0;
        for (Question q : removeFreetext(survey.questions))
            for (SurveyDatum c : q.options.values()) {
                for (Set<Block> path : paths) {
                    List<SurveyDatum> variants = getEquivalentAnswerVariants(q, c);
                    List<? extends SurveyResponse> responsesThisPath = pathMap.get(path);
                    double ansThisPath = 0.0;
                    for (SurveyResponse r : responsesThisPath) {
                        boolean responseInThisPath = r.surveyResponseContainsAnswer(variants);
                        if (responseInThisPath) {
                            //SurveyMan.LOGGER.info("Found an answer on this path!");
                            ansThisPath += 1.0;
                        }
                    }
                    double p = ansThisPath / (double) totalResponses;
                    retval += log2(p) * p;
                }
            }
        return -retval;
    }

    /**
     * Returns all questions in a block list (typically the topLevelBlocks of a Survey).
     * @param blockList A list of blocks we would like to traverse.
     * @return A list of questions.
     */
    public static List<Question> getQuestions(
            final Set<Block> blockList)
    {
        List<Question> questions = new ArrayList<>();
        for (Block block : blockList) {
            if (block.getBranchParadigm() != Block.BranchParadigm.ALL)
                questions.addAll(block.questions);
            else {
                questions.add(block.questions.get(new Random().nextInt(block.questions.size())));
            }
            questions.addAll(getQuestions(new HashSet<>(block.subBlocks)));
        }
        return questions;
    }

    /**
     * Returns the maximum possible entropy for a single Question.
     * @param question The question of interest.
     * @return An entropy-based calculation that distributes mass across all possible options equally.
     */
    private static double maxEntropyOneQuestion(
            Question question)
    {
        double retval = 0.0;
        int numOptions = question.options.size();
        if (numOptions != 0) {
            retval += log2((double) numOptions);
        }
        return retval;
    }


    /**
     * Returns the total entropy for a list of Questions.
     * @param questionList The list of questions whose entropy we want.
     * @return The total entropy for a list of Questions.
     */
    private static double maxEntropyQlist(List<Question> questionList)
    {
        double retval = 0.0;
        for (Question q : questionList) {
            retval += maxEntropyOneQuestion(q);
        }
        return retval;
    }


    /**
     * Returns the path with the highest entropy.
     * @param blists List of paths through the survey, where the path is defined by a list of blocks.
     * @return The path through the survey having the maximum entropy, expressed as a block list.
     */
    private static Set<Block> getMaxPathForEntropy(List<Set<Block>> blists)
    {
        Set<Block> retval = null;
        double maxEnt = 0.0;
        for (Set<Block> blist : blists) {
            double ent = maxEntropyQlist(getQuestions(blist));
            if (ent > maxEnt) {
                maxEnt = ent;
                retval = blist;
            }
        }
        return retval;
    }

    /**
     * The public method used to compute the maximum number of bits needed to represent this survey.
     * @param survey Input survey whose max entropy we want to compute.
     * @return The maximum possible entropy for this source.
     */
    public static double getMaxPossibleEntropy(
            Survey survey)
    {
        double maxEnt = maxEntropyQlist(getQuestions(getMaxPathForEntropy(getPaths(survey))));
        SurveyMan.LOGGER.info(String.format("Maximum possible entropy for survey %s: %f", survey.sourceName, maxEnt));
        return maxEnt;
    }

    public static int minimumPathLength(Survey survey){
        List<Set<Block>> paths = getPaths(survey);
        int min = Integer.MAX_VALUE;
        for (Set<Block> path : paths) {
            int pathLength = getQuestions(path).size();
            if (pathLength < min)
                min = pathLength;
        }
        SurveyMan.LOGGER.info(String.format("Survey %s has minimum path length of %d", survey.sourceName, min));
        return min;
    }

    public static int maximumPathLength(Survey survey) {
        List<Set<Block>> paths = getPaths(survey);
        int max = Integer.MIN_VALUE;
        for (Set<Block> path : paths) {
            int pathLength = getQuestions(path).size();
            if (pathLength > max) {
                max = pathLength;
            }
        }
        SurveyMan.LOGGER.info(String.format("Survey %s has maximum path length of %d", survey.sourceName, max));
        return max;

    }

    public static double averagePathLength(
            Survey survey)
            throws SurveyException
    {
        int n = 5000;
        int stuff = 0;
        for (int i = 0 ; i < n ; i++) {
            RandomRespondent rr = new RandomRespondent(survey, RandomRespondent.AdversaryType.UNIFORM);
            stuff += rr.getResponse().getNonCustomResponses().size();
        }
        double avg = (double) stuff / n;
        SurveyMan.LOGGER.info(String.format("Survey %s has average path length of %f", survey.sourceName, avg));
        return avg;
    }

    /**
     * When used without the survey argument, this returns frequencies that do not calculate smoothing.
     * @param responses The list of actual or simulated responses to the survey
     * @return A map from question ids to maps of option ids to counts.
     */
    public static Map<String, Map<String, Integer>> makeFrequencies(
            List<? extends SurveyResponse> responses)
    {
        return makeFrequencies(responses, null);
    }

    /**
     * Creates a frequency map for the actual responses to the survey. If the survey argument is not null, it will c
     * calculate LaPlace smoothing.
     * @param responses The list of actual or simulated responses to the survey.
     * @param survey The survey these respondents answered.
     * @return A map from question ids to a map of option ids to counts.
     */
    public static Map<String, Map<String, Integer>> makeFrequencies(
            List<? extends SurveyResponse> responses,
            Survey survey)
    {
        // map from question id to a map from answer id to counts
        Map<String, Map<String, Integer>> retval = new HashMap<>();
        Set<String> allAnswerOptionIdsSelected = new HashSet<>();
        for (SurveyResponse sr : responses) {
            for (IQuestionResponse qr : sr.getNonCustomResponses()) {
                String quid = qr.getQuestion().id;
                // get the answer option map associated with this question
                Map<String, Integer> tmp;
                if (retval.containsKey(quid)) {
                    tmp = retval.get(quid);
                } else {
                    tmp = new HashMap<>();
                    retval.put(quid, tmp);
                }
                List<String> aids = OptTuple.getCids(qr.getOpts());
                for (String cid : aids) {
                    allAnswerOptionIdsSelected.add(cid);
                    if (tmp.containsKey(cid))
                        tmp.put(cid, tmp.get(cid) + 1);
                    else tmp.put(cid, 1);
                }
            }
        }
        // LaPlace (+1 smoothing)
        if (survey != null) {
            int numberNeedingSmoothing = 0;
            for (Question q : survey.questions) {
                for (SurveyDatum c : q.options.values()) {
                    if (!retval.containsKey(q.id)) {
                        retval.put(q.id, new HashMap<String, Integer>());
                    }
                    retval.get(q.id).put(c.getId(), 1);
                    if (!allAnswerOptionIdsSelected.contains(c.getId())) {
                        numberNeedingSmoothing++;
                    }
                }
            }
            if (numberNeedingSmoothing > 0)
                SurveyMan.LOGGER.info("Number needing smoothing " + numberNeedingSmoothing);
        }
        return retval;
    }

    public static Map<String, Map<String, Double>> makeProbabilities(
            Map<String, Map<String, Integer>> frequencies)
    {
        Map<String, Map<String, Double>> retval = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : frequencies.entrySet()) {
            String quid = e.getKey();
            Map<String, Integer> map = e.getValue();
            double total = 0.0;
            for (Integer i : map.values()) {
                total += i;
            }
            retval.put(quid, new HashMap<String, Double>());
            for (String cid : map.keySet()) {
                retval.get(quid).put(cid, map.get(cid) / total);
            }
        }
        return retval;
    }

    public static double getLLForResponse(
            List<IQuestionResponse> questionResponses,
            Map<String, Map<String, Double>> probabilities) {
        if (questionResponses == null)
            return -0.0;
        double ll = 0.0;
        for (IQuestionResponse questionResponse : questionResponses) {
            String qid = questionResponse.getQuestion().id;
            for (String cid : OptTuple.getCids(questionResponse.getOpts())) {
                ll += log2(probabilities.get(qid).get(cid));
            }
        }
        return ll;
    }

    public static double getEntropyForResponse(
            SurveyResponse surveyResponse,
            Map<String, Map<String, Double>> probabilities)
    {
        double ent = 0.0;
        for (IQuestionResponse questionResponse : surveyResponse.getNonCustomResponses()) {
            String qid = questionResponse.getQuestion().id;
            for (String cid : OptTuple.getCids(questionResponse.getOpts())) {
                double p = probabilities.get(qid).get(cid);
                assert p > 0.0;
                ent += p * log2(p);
            }
        }
        return -ent;
    }

    private static List<Double> calculateLogLikelihoods(
            SurveyResponse base,
            List<? extends SurveyResponse> responses,
            Map<String, Map<String, Double>> probabilities)
            throws SurveyException
    {
        List<Double> retval = new LinkedList<>();
        // get the first response count
        int responseSize = base.getNonCustomResponses().size();
        for (SurveyResponse sr : responses) {
            List<IQuestionResponse> questionResponses = getResponseSubset(base, sr);
            int thisresponsesize = questionResponses.size();
            if (thisresponsesize == 0)
                continue;
            assert responseSize == thisresponsesize : String.format(
                    "Expected %d responses, got %d",
                    responseSize,
                    thisresponsesize);
            retval.add(getLLForResponse(questionResponses, probabilities));
        }
        return retval;
    }

    private static List<IQuestionResponse> getResponseSubset(
            SurveyResponse base,
            SurveyResponse target
    ) throws SurveyException {
        // These will be used to generate the return value.
        List<IQuestionResponse> responses = new ArrayList<>();

        // For each question in our base response, check whether the target has answered that question or one of its
        // variants.
        for (IQuestionResponse qr : base.getNonCustomResponses()) {
            if (Question.customQuestion(qr.getQuestion().id))
                continue;
            // Get the variants for this question.
            List<Question> variants = qr.getQuestion().getVariants();
            boolean variantFound = false;
            for (Question q : variants) {
                if (target.hasResponseForQuestion(q)) {
                    responses.add(target.getResponseForQuestion(q));
                    assert !variantFound : "Answers to two of the same variant found.";
                    variantFound = true;
                }
            }
            if (!variantFound)
                return new ArrayList<>();
        }
        return responses;
    }


    /**
     * Generates the bootstrap sample for the input response and the specified number of iterations. Default 2000.
     * @param responses The list of actual or simulated responses to the survey.
     * @param iterations The number of bootstrap samples we should generate.
     * @return The bootstrapped sample of possible survey responses.
     */
    public static List<List<SurveyResponse>> generateBootstrapSample(
            List<? extends SurveyResponse> responses,
            int iterations)
    {
        List<List<SurveyResponse>> retval = new ArrayList<>();
        for (int i = 0; i < iterations; i++) {
            List<SurveyResponse> sample = new ArrayList<>();
            for (int j = 0 ; j < responses.size() ; j++) {
                sample.add(responses.get(Interpreter.random.nextInt(responses.size())));
            }
            retval.add(sample);
        }
        return retval;
    }

    private static Map<Set<Question>, List<List<SurveyResponse>>> cache = new HashMap<>();
    protected static List<List<SurveyResponse>> cachedQuestionSet(SurveyResponse sr,
                                                                  List<? extends SurveyResponse> responses)
    {

        Set<Question> questions = new HashSet<>();
        for (IQuestionResponse qr : sr.getAllResponses())
            questions.add(qr.getQuestion());

        if (cache.containsKey(questions))
            return cache.get(questions);

        List<List<SurveyResponse>> bssample = generateBootstrapSample(responses, bootstrapIterations);
        cache.put(questions, bssample);
        return bssample;
    }

    private static Map<Classifier, Map<List<List<SurveyResponse>>, List<Double>>> means = new HashMap<>();
    protected static List<Double> cachedMeans(SurveyResponse sr,
                                              List<? extends SurveyResponse> responses,
                                              Map<String, Map<String, Double>> probabilities,
                                              Classifier classifier)
    throws SurveyException
    {

        List<Double> retval = new ArrayList<>();
        List<List<SurveyResponse>> bsSample = cachedQuestionSet(sr, responses);
        if (means.containsKey(classifier) && means.get(classifier).containsKey(bsSample))
            return means.get(classifier).get(bsSample);


        for (List<? extends SurveyResponse> sample : bsSample) {
            double total = 0.0;
            for (SurveyResponse surveyResponse: sample) {
                switch (classifier) {
                    case LOG_LIKELIHOOD:
                        total += getLLForResponse(getResponseSubset(sr, surveyResponse), probabilities);
                        break;
                    case ENTROPY:
                        total += getEntropyForResponse(surveyResponse, probabilities);
                        break;
                    default:
                        throw new RuntimeException("FML");

                }
            }
            retval.add(total / sample.size());
        }
        assert retval.size() == bsSample.size();
        Collections.sort(retval);
        assert retval.get(0) < retval.get(retval.size() - 1) :
                    String.format("Ranked means expected mean at position 0 to be greater than the mean at %d (%f < %f).",
                            retval.size(), retval.get(0), retval.get(retval.size() - 1));
        if (!means.containsKey(classifier))
            means.put(classifier, new HashMap<List<List<SurveyResponse>>, List<Double>>());
        Map<List<List<SurveyResponse>>, List<Double>> classifiersMeans = means.get(classifier);
        classifiersMeans.put(bsSample, retval);
        return retval;
    }

    /**
     * Returns true if the response is valid, on the basis of the log likelihood.
     * @param survey The survey these respondents answered.
     * @param sr The survey response we are classifying.
     * @param responses The list of actual or simulated responses to the survey
     * @param smoothing Boolean indicating whether we should smooth our calculation of answer frequencies.
     * @param alpha The cutoff used for determining whether a likelihood is too low (a percentage of area under the curve).
     * @return Decision indicating whether the input response is valid.
     */
    public static boolean logLikelihoodClassification(
            Survey survey,
            SurveyResponse sr,
            List<? extends SurveyResponse> responses,
            boolean smoothing,
            double alpha
    ) throws SurveyException {

        Map<String, Map<String, Double>> probabilities = makeProbabilities(makeFrequencies(responses, smoothing ? survey : null));
        List<Double> lls = calculateLogLikelihoods(sr, responses, probabilities);
        Set<Double> llSet = new HashSet<>(lls);

        if (llSet.size() > 5) {

            double thisLL = getLLForResponse(sr.getNonCustomResponses(), probabilities);
            List<Double> means = cachedMeans(sr, responses, probabilities, Classifier.LOG_LIKELIHOOD);
            //SurveyMan.LOGGER.info(String.format("Range of means: [%f, %f]", means.get(0), means.get(means.size() -1)));
            double threshHold = means.get((int) Math.floor(alpha * means.size()));
            //SurveyMan.LOGGER.info(String.format("Threshold: %f\tLL: %f", threshHold, thisLL));
            sr.setScore(thisLL);
            sr.setThreshold(threshHold);
            return thisLL > threshHold;

        } else return true;
    }

    public static void lpoClassification(
            Survey survey,
            List<? extends SurveyResponse> responses,
            boolean smoothing,
            double alpha,
            double epsilon
    ) throws SurveyException
    {
        Map<Question, List<SurveyDatum>> lpos = new HashMap<>();
        Map<String, Map<String, Integer>> probabilities = makeFrequencies(responses, smoothing ? survey : null);
        for (Question q: survey.getQuestionListByIndex()) {

            if (!probabilities.containsKey(q.id))
                continue;

            Map<String, Integer> cmap = probabilities.get(q.id);
            Integer[] crap = new Integer[cmap.size()];
            cmap.values().toArray(crap);
            Arrays.sort(crap);

            if (crap.length > 1)
                assert crap[0] <= crap[1];
            else continue;

            List<SurveyDatum> theseLPOs = new ArrayList<>();

            for (Map.Entry<String, Integer> e : cmap.entrySet()) {
                if (e.getValue().equals(crap[0])) {
                    theseLPOs.add(q.getOptById(e.getKey()));
                    break;
                }
            }

            if (theseLPOs.size() == cmap.size())
                continue;

            for (int i = 1; i < crap.length; i++) {
                if (crap[i] > (1 + epsilon) * crap[i-1])
                    break;
                else {
                    for (Map.Entry<String, Integer> e : cmap.entrySet()) {
                        if (e.getValue().equals(crap[i])) {
                            theseLPOs.add(q.getOptById(e.getKey()));
                            break;
                        }
                    }
                }
            }
            lpos.put(q, theseLPOs);
        }
        // let delta be 0.5
        double delta = 0.5;
        double mu = 0.0;
        for (Question q : survey.questions) {
            if (lpos.containsKey(q))
                mu += lpos.get(q).size() / (1.0 * q.options.size());
        }
        double threshold = (1 - delta) * mu;
        for (SurveyResponse sr : responses) {
            int ct = 0;
            for (IQuestionResponse questionResponse : sr.getAllResponses()) {
                Question q = questionResponse.getQuestion();
                if (lpos.containsKey(q)) {
                    List<SurveyDatum> theseLPOs = lpos.get(questionResponse.getQuestion());
                    if ((q.exclusive && theseLPOs.contains(questionResponse.getAnswer())) ||
                        (!q.exclusive && theseLPOs.containsAll(questionResponse.getAnswers())))
                        ct += 1;
                }
            }
            sr.setThreshold(threshold);
            sr.setScore(ct);
            sr.setComputedValidityStatus(ct > threshold ? KnownValidityStatus.NO : KnownValidityStatus.YES);
        }
    }

        /**
         * Return true if the response is valid, on the basis of an entropy-based metric.
         * @param survey The survey these respondents answered.
         * @param sr The survey response we are classifying.
         * @param responses The list of actual or simulated responses to the survey
         * @param smoothing Boolean indicating whether we should smooth our calculation of answer frequencies.
         * @param alpha The cutoff used for determining whether a likelihood is too low (a percentage of area under the curve).
         * @return boolean indicating whether the response is valid.
         */
    public static boolean entropyClassification(
            Survey survey,
            SurveyResponse sr,
            List<? extends SurveyResponse> responses,
            boolean smoothing,
            double alpha
    ) throws SurveyException
    {
        // basically the same as logLikelihood, but scores are p * log p, rather than straight up p
        Map<String, Map<String, Double>> probabilities = makeProbabilities(makeFrequencies(responses, smoothing ? survey : null));

        List<Double> lls = calculateLogLikelihoods(sr, responses, probabilities);
        Set<Double> scoreSet = new HashSet<>(lls);
        if (scoreSet.size() > 5) {
            double thisEnt = getEntropyForResponse(sr, probabilities);
            List<Double> means = cachedMeans(sr, responses, probabilities, Classifier.ENTROPY);
            double threshHold = means.get((int) Math.ceil(alpha * means.size()));
            sr.setThreshold(threshHold);
            sr.setScore(thisEnt);
            //SurveyMan.LOGGER.debug(String.format("This entropy: %f\tThis threshold:%f", thisEnt, threshHold));
            return thisEnt < threshHold;
        } else return true;
    }

    protected static void computeRanks(
            double[] xranks,
            List xs)
    {
        Object lastComponent = null;
        int startRun = Integer.MAX_VALUE;
        int endRun = 0;

        for (int i = 0 ; i < xs.size() ; i++) {
            if (lastComponent==null || !lastComponent.equals(xs.get(i))) {
                if (endRun > startRun) {
                    // then we need to distribute the ranks of the run of equals
                    double denominator = endRun - startRun + 1.0;
                    int numerator = ((endRun^2 + endRun) / 2) - ((startRun^2 + startRun) / 2) + startRun;
                    double rank = numerator / denominator;
                    for (; startRun <= endRun ; startRun++){
                        xranks[startRun] = rank;
                    }
                }
                xranks[i] = i+1;
                lastComponent = xs.get(i);
            } else {
                if (startRun >= endRun)
                    startRun = i;
                endRun = i;
            }
        }
    }

    protected static double spearmansRho(
            Map<String, IQuestionResponse> listA,
            Map<String, IQuestionResponse> listB)
    {
        // order the IQuestionResponses
        List<SurveyDatum> xs = new ArrayList<SurveyDatum>(), ys = new ArrayList<SurveyDatum>();

        for (IQuestionResponse qr : listA.values()) {
            xs.add(qr.getOpts().get(0).c);
        }
        for (IQuestionResponse qr : listB.values()) {
            ys.add(qr.getOpts().get(0).c);
        }
        Collections.sort(xs);
        Collections.sort(ys);

        // compute ranks
        double[] xranks = new double[xs.size()];
        double[] yranks = new double[ys.size()];
        computeRanks(xranks, xs);
        computeRanks(yranks, ys);

        double sumOfSquares = 0.0;
        for (int i = 0; i < xranks.length; i++){
            sumOfSquares += Math.pow(xranks[i] - yranks[i], 2);
        }

        int n = xranks.length;

        return 1 - ((6 * sumOfSquares) / (n * (n^2 - 1)));
    }

    protected static double cellExpectation(
            int[][] contingencyTable,
            int i,
            int j,
            int n)
    {
        int o1 = 0, o2 = 0;
        for (int[] aContingencyTable : contingencyTable)
            o1 += aContingencyTable[j];
        for (int c = 0 ; c < contingencyTable[0].length; c++)
            o2 += contingencyTable[i][c];
        return o1 * o2 / ((double) n);
    }

    protected static double chiSquared(
            int[][] contingencyTable,
            Object[] categoryA,
            Object[] categoryB)
    {
        double testStatistic = 0.0;
        int numSamples = 0;
        for (int[] aContingencyTable : contingencyTable)
            for (int anAContingencyTable : aContingencyTable) numSamples += anAContingencyTable;
        for (int r = 0; r < categoryA.length; r++)
            for (int c = 0; c < categoryB.length; c++) {
                double eij = cellExpectation(contingencyTable, r, c, numSamples);
                if (eij == 0.0)
                    continue;
                testStatistic += Math.pow(contingencyTable[r][c] - eij, 2.0) / eij;
            }
        return testStatistic;
    }


    protected static double cramersV(Map<String, IQuestionResponse> listA, Map<String,IQuestionResponse> listB)
    {
        Question sampleQA = ((IQuestionResponse) listA.values().toArray()[0]).getQuestion();
        Question sampleQB = ((IQuestionResponse) listB.values().toArray()[0]).getQuestion();

        assert listA.size() == listB.size() : String.format(
                "Question responses have different sizes:\n%d for question %s\n%d for question %s",
                listA.size(), sampleQA,
                listB.size(), sampleQB
        );

        // get the categories for the contingency table:
        final SurveyDatum[] categoryA = new SurveyDatum[sampleQA.options.values().size()];
        final SurveyDatum[] categoryB = new SurveyDatum[sampleQB.options.values().size()];
        sampleQA.options.values().toArray(categoryA);
        sampleQB.options.values().toArray(categoryB);

        int r = categoryA.length;
        int c = categoryB.length;
        if (r==0 || c==0)
            return -0.0;
        // get the observations and put them in a contingency table:
        int[][] contingencyTable = new int[r][c];
        // initialize
        for (int i = 0; i < r; i++) Arrays.fill(contingencyTable[i], 0);
        for (Map.Entry<String, IQuestionResponse> entry : listA.entrySet()) {
            // Tabulate the places where A and B agree
            String id = entry.getKey();
            SurveyDatum ansA = entry.getValue().getOpts().get(0).c;
            SurveyDatum ansB = listB.get(id).getOpts().get(0).c;
            int i = 0, j = 0;
            for (; i < r ; i++)
                if (categoryA[i].equals(ansA))
                    break;
            for (; j < c ; j++)
                if (categoryB[j].equals(ansB))
                    break;
            // If they never co-occur
            if (i==r || j==c) {
                SurveyMan.LOGGER.warn(
                        String.format("No co-occurances of %s and %s -- consider using smoothing",
                        ansA, ansB));
                continue;
            }
            contingencyTable[i][j] += 1;
        }

        return Math.sqrt((chiSquared(contingencyTable, categoryA, categoryB) / listA.size()) / Math.min(c - 1, r - 1));
    }

    protected static double mannWhitney(
            Question q1,
            Question q2,
            List<SurveyDatum> list1,
            List<SurveyDatum> list2)
    {
        if (list1.size()==0 || list2.size()==0)
            return -0.0;
        // make ranks on the basis of the source row index
        Collections.sort(list1);
        Collections.sort(list2);
        double[] list1ranks = new double[list1.size()];
        double[] list2ranks = new double[list2.size()];
        for (int i = 0 ; i < list1.size() ; i++)
            list1ranks[i] = (double) list1.get(i).getSourceRow() - q1.getSourceRow() + 1;
        for (int i = 0 ; i < list2.size() ; i++)
            list2ranks[i] = (double) list2.get(i).getSourceRow() - q2.getSourceRow() + 1;
        // default constructor for mann whitney averages ties.
        return new MannWhitneyUTest().mannWhitneyUTest(list1ranks, list2ranks);
    }

    /**
     * Simulates a survey of 100% random uniform respondents over sampleSize and calculates a prior on false correlation.
     * @param survey The survey these respondents answered.
     * @param sampleSize The sample size the survey writer intends to use during the full-scale study.
     * @return Empirical false correlation.
     * @throws SurveyException
     */
    public static Map<Question, Map<Question, CorrelationStruct>> getFrequenciesOfRandomCorrelation(
            Survey survey,
            int sampleSize)
            throws SurveyException
    {

        Map<Question, Map<Question, CorrelationStruct>> corrs =
                new HashMap<Question, Map<Question, CorrelationStruct>>();
        List<RandomRespondent> randomRespondents =
                new ArrayList<RandomRespondent>();

        for (int i = 0 ; i < sampleSize; i++){
            randomRespondents.add(new RandomRespondent(survey, RandomRespondent.AdversaryType.UNIFORM));
        }

        for (Question q1 : survey.questions) {
            if (!q1.exclusive) continue;
            assert !q1.freetext : String.format(
                    "Cannot have a freetext question with exclusive set to true (%s)", q1);
            for (Question q2: survey.questions) {
                if (!q2.exclusive) continue;
                assert !q2.freetext : String.format(
                        "Cannot have a freetext question with exclusive set to true (%s)", q2);
                // get responses having answered both questions
                Map<String, IQuestionResponse> q1responses = new HashMap<String, IQuestionResponse>();
                Map<String, IQuestionResponse> q2responses = new HashMap<String, IQuestionResponse>();
                for (RandomRespondent rr : randomRespondents) {

                    IQuestionResponse qr1 = null;
                    IQuestionResponse qr2 = null;

                    for (IQuestionResponse qr : rr.getResponse().getNonCustomResponses()) {
                        if (qr.getQuestion().equals(q1))
                            qr1 = qr;
                        if (qr.getQuestion().equals(q2))
                            qr2 = qr;
                        if (qr1!=null && qr2!=null)
                            break;
                    }

                    if (qr1!=null && qr2!=null){
                        q1responses.put(rr.id, qr1);
                        q2responses.put(rr.id, qr2);
                    }
                }
                // compute the appropriate correlation coefficient
                Map<Question, CorrelationStruct> stuff = new HashMap<Question, CorrelationStruct>();
                if (q1.ordered && q2.ordered)
                    stuff.put(q2, new CorrelationStruct(
                            CoefficentsAndTests.RHO,
                            spearmansRho(q1responses, q2responses),
                            q1,
                            q2,
                            q1responses.size(),
                            q2responses.size()));
                else
                    stuff.put(q2, new CorrelationStruct(
                            CoefficentsAndTests.V,
                            cramersV(q1responses, q2responses),
                            q1,
                            q2,
                            q1responses.size(),
                            q2responses.size()
                    ));
                corrs.put(q1, stuff);
                // count how many p-values are below the threshhold.
            }
        }
        return corrs;
    }

    private static List<IQuestionResponse> removeCustomQuestions(SurveyResponse sr)
    {
        // remove custom questions
        List<IQuestionResponse> qrs = new ArrayList<IQuestionResponse>();
        for (IQuestionResponse qr : sr.getNonCustomResponses()) {
            if (!Question.customQuestion(qr.getQuestion().id))
                qrs.add(qr);
        }
        return qrs;
    }

    /**
     * Aggregates the breakoff according to the last position answered.
     * @param survey The survey these respondents answered.
     * @param responses The list of actual or simulated responses to the survey.
     * @return A BreakoffByPosition object containing all of the values just computed.
     */
    public static BreakoffByPosition calculateBreakoffByPosition(
            Survey survey,
            List<? extends SurveyResponse> responses)
    {
        // for now this just reports breakoff, rather than statistically significant breakoff
        BreakoffByPosition breakoffMap = new BreakoffByPosition(survey);
        for (SurveyResponse sr : responses) {
            int answerLength = sr.getNonCustomResponses().size();
            //TODO(etosch): remove legit final positions from breakoff.
            breakoffMap.update(answerLength);
        }
        return breakoffMap;

    }

    /**
     * Aggregates the breakoff according to which question was last answered.
     * @param survey The survey these respondents answered.
     * @param responses The list of actual or simulated responses to the survey.
     * @return A BreakoffByQuestion object containing all of the values just computed.
     */
    public static BreakoffByQuestion calculateBreakoffByQuestion(
            Survey survey,
            List<? extends SurveyResponse> responses)
    {
        BreakoffByQuestion breakoffMap = new BreakoffByQuestion(survey);
        for (SurveyResponse sr : responses) {
            // get the last question responded to
            List<IQuestionResponse> qrs = sr.getNonCustomResponses();
            IQuestionResponse lastQuestionAnswered = qrs.get(0);
            for (IQuestionResponse qr : qrs)
               if (qr.getIndexSeen() > lastQuestionAnswered.getIndexSeen())
                   lastQuestionAnswered = qr;
            //TODO(etosch): remove legit final questions from breakoff.
            breakoffMap.update(lastQuestionAnswered.getQuestion());
        }
        return breakoffMap;
    }

    /**
     * Searches for significant wording biases observed in survey responses.
     * @param survey The survey these respondents answered.
     * @param responses The list of actual or simulated responses to the survey.
     * @param alpha The cutoff used for determining whether the bias is significant.
     * @return A WordingBiasStruct object containing all of the values just computed.
     * @throws SurveyException
     */
    public static WordingBiasStruct calculateWordingBiases(
            Survey survey,
            List<? extends SurveyResponse> responses,
            double alpha)
            throws SurveyException
    {
        WordingBiasStruct retval = new WordingBiasStruct(survey, alpha);
        // get variants
        for (Block b : survey.getAllBlocks()) {
            if (b.getBranchParadigm().equals(Block.BranchParadigm.ALL)) {
                List<Question> variants = b.branchQ.getVariants();
                for (Question q1: variants) {
                    if (!q1.exclusive)
                        continue;
                    for (Question q2: variants) {
                        assert q2.exclusive : "All question variants must have the same parameter settings.";
                        List<SurveyDatum> q1answers = new ArrayList<>();
                        List<SurveyDatum> q2answers = new ArrayList<SurveyDatum>();
                        for (SurveyResponse sr : responses) {
                            if (sr.hasResponseForQuestion(q1))
                                q1answers.add(sr.getResponseForQuestion(q1).getOpts().get(0).c);
                            if (sr.hasResponseForQuestion(q2))
                                q2answers.add(sr.getResponseForQuestion(q2).getOpts().get(0).c);
                        }
                        if (q1.exclusive && q2.exclusive) {
                            retval.update(b, q1, q2, new CorrelationStruct(
                                    CoefficentsAndTests.U,
                                    mannWhitney(q1, q2, q1answers, q2answers),
                                    q1,
                                    q2,
                                    q1answers.size(),
                                    q2answers.size())
                            );
                        } else {
                            // sort by their source rows
                            List<SurveyDatum> categoryA = Arrays.asList(q1.getOptListByIndex());
                            List<SurveyDatum> categoryB = Arrays.asList(q2.getOptListByIndex());
                            Collections.sort(categoryA);
                            Collections.sort(categoryB);
                            int[][] contingencyTable = new int[categoryA.size()][2];
                            // initialize the contingency table
                            for (int i = 0 ; i < categoryA.size() ; i++) {
                                contingencyTable[i][0] = 0;
                                contingencyTable[i][1] = 0;
                            }

                            for (SurveyDatum c : q1answers)
                                contingencyTable[0][categoryA.indexOf(c)] += 1;
                            for (SurveyDatum c : q2answers)
                                contingencyTable[0][categoryA.indexOf(c)] += 1;

                            retval.update(b, q1, q2, new CorrelationStruct(
                                    CoefficentsAndTests.CHI,
                                    chiSquared(contingencyTable, categoryA.toArray(), new List[]{q1answers, q2answers}),
                                    q1,
                                    q2,
                                    q1answers.size(),
                                    q2answers.size())
                            );
                        }
                    }
                }
            }
        }
        return retval;
    }

    /**
     * Searches for significant order biases observed in survey responses.
     * @param survey The survey these respondents answered.
     * @param responses The list of actual or simulated responses to the survey.
     * @param alpha The cutoff used for determining whether the bias is significant.
     * @return An OrderBiasStruct object containing all of the values just computed.
     * @throws SurveyException
     */
    public static OrderBiasStruct calculateOrderBiases(
            Survey survey,
            List<? extends SurveyResponse> responses,
            double alpha)
            throws SurveyException
    {
        OrderBiasStruct retval = new OrderBiasStruct(survey, alpha);
        for (Question q1 : survey.questions) {
            for (Question q2 : survey. questions) {
                if (!q1.exclusive || q1.equals(q2))
                    break;
                else {
                    // q1 answers when q1 comes first
                    List<SurveyDatum> q1q2 = new ArrayList<SurveyDatum>();
                    // q1 answers when q1 comes second
                    List<SurveyDatum> q2q1 = new ArrayList<SurveyDatum>();
                    for (SurveyResponse sr : responses) {
                        if (sr.hasResponseForQuestion(q1) && sr.hasResponseForQuestion(q2)) {
                            IQuestionResponse qr1 = sr.getResponseForQuestion(q1);
                            IQuestionResponse qr2 = sr.getResponseForQuestion(q2);
                            if (qr1.getIndexSeen() < qr2.getIndexSeen())
                                // add the response to question 1 to the list of q1s that precede q2s
                                q1q2.add(qr1.getOpts().get(0).c);
                            else if (qr1.getIndexSeen() > qr2.getIndexSeen())
                                // add the response to question 1 to the list of q1s that succeed q2s
                                q2q1.add(qr1.getOpts().get(0).c);
                        }
                    }
                    if (q1.ordered && q2.ordered)
                        retval.update(q1, q2, new CorrelationStruct(
                                CoefficentsAndTests.U,
                                mannWhitney(q1, q2, q1q2, q2q1),
                                q1,
                                q2,
                                q1q2.size(),
                                q2q1.size())
                        );
                    else {
                        SurveyDatum[] categoryA = q1.getOptListByIndex();
                        int[][] contingencyTable = new int[categoryA.length][2];
                        for (int i = 0 ; i < categoryA.length ; i++ ){
                            contingencyTable[i][0] = 0;
                            contingencyTable[i][1] = 0;
                        }
                        // if the difference in the observations is large, the orderings is incomparable
                        // make this more principled in the future.
                        double ratio = q1q2.size() / (double) q1q2.size();
                        if (q1q2.size() < 5 || q2q1.size() < 5 || (ratio > 0.8 && ratio < 1.2))
                            break;
                        for (SurveyDatum c : q1q2)
                            contingencyTable[Arrays.asList(categoryA).indexOf(c)][0] += 1;
                        for (SurveyDatum c : q2q1)
                            contingencyTable[Arrays.asList(categoryA).indexOf(c)][1] += 1;
                        retval.update(q1, q2, new CorrelationStruct(
                                CoefficentsAndTests.CHI,
                                chiSquared(contingencyTable, categoryA, new List[]{q1q2, q2q1}),
                                q1,
                                q2,
                                q1q2.size(),
                                q2q1.size()));
                    }
                }
            }
        }
        return retval;
    }

    private static void labelValidity(List<CentroidCluster<SurveyResponse>> clusters) {
        // get max representative validity for each cluster and label responses according to that.
        for (CentroidCluster cluster : clusters) {
            Map<KnownValidityStatus, Integer> counts = new HashMap<>();
            for (Object point : cluster.getPoints()) {
                SurveyResponse sr = (SurveyResponse) point;
                if (counts.containsKey(sr.getKnownValidityStatus()))
                    counts.put(sr.getKnownValidityStatus(), counts.get(sr.getKnownValidityStatus())+1);
                else counts.put(sr.getKnownValidityStatus(), 1);
            }
            int max = 0;
            KnownValidityStatus knownValidityStatus  = KnownValidityStatus.MAYBE;
            for (Map.Entry<KnownValidityStatus, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > max) {
                    max = entry.getValue();
                    knownValidityStatus = entry.getKey();
                }
            }
            for (Object point : cluster.getPoints()) {
                ((SurveyResponse) point).setComputedValidityStatus(knownValidityStatus);
            }
        }
    }

    private static void clusterResponses(
            List<? extends SurveyResponse> responses,
            int k,
            boolean supervised)
    {
            int maxIterations = 50; //responses.size() * 2;
            HammingDistance hamming = new HammingDistance();
            KMeansPlusPlusClusterer<SurveyResponse> responseClusters =
                    new KMeansPlusPlusClusterer<SurveyResponse>(k, maxIterations, hamming);
            List<CentroidCluster<SurveyResponse>> clusters =
                    responseClusters.cluster(new ArrayList<>(responses));
            for (int i = 0; i < clusters.size(); i++) {
                CentroidCluster cluster = clusters.get(i);
                Clusterable center = cluster.getCenter();
                for (Object point : cluster.getPoints()) {
                    SurveyResponse sr = (SurveyResponse) point;
                    sr.center = center;
                    sr.clusterLabel = "cluster_" + i;
                }
            }
        if (supervised) {
            labelValidity(clusters);
        }
    }

    private static void linearlyClassifyResponses(Survey survey, List<? extends SurveyResponse> responses){
        // represent scores as matrices
        int n = responses.size();
        int d = survey.questions.size();
        double[][] scores = new double[d][n];
        for (int i = 0 ; i < n; i++) {
            SurveyResponse sr = responses.get(i);
            scores[i] = sr.getPoint();
        }
        // calculate means
        double[] mus = new double[d];
        for (int i = 0; i < d; i++) {
            double total = 0.0;
            for (int j = 0; j < n; j++)
                total += scores[i][j];
            mus[i] = total / d;
        }
        // create matrix of means
        double[][] mmus = new double[n][d];
        for (int i = 0; i < n; i++)
            mmus[n] = mus;

        BlockRealMatrix m = new BlockRealMatrix(scores).subtract(new BlockRealMatrix(mmus).transpose()).transpose(); // D x N
        BlockRealMatrix squareM = m.transpose().multiply(m); // N  x N
        EigenDecomposition e = new EigenDecomposition(squareM);
        RealVector firstEigenVector = e.getEigenvector(0); // N x 1
        double[][] reduced = new double[1][n]; // 1 x N
        reduced[0] = firstEigenVector.toArray(); // 1 x N
        // am i not *Actually* interested in the lowest variance in the data?
        BlockRealMatrix reducedData = m.multiply(new BlockRealMatrix(reduced).transpose()); // D x 1
        // use the learned basis vectors to find a partition
        //TODO(etosch): finish this.
    }

    /**
     * Classifies the input responses according to the classifier. The DynamicSurveyResponse objects will hold the
     * computed classification, and the method will return a classification structure for easy printing and jsonizing.
     * @param survey The survey these respondents answered.
     * @param responses The list of actual or simulated responses to the survey.
     * @param classifier The enum corresponding to the classifier type.
     * @param smoothing A boolean value indicating whether the frequencies of responses should be smoothed.
     * @param alpha The cutoff used for determining whether validity is exceptionally low.
     * @return A ClassifiedRespondentsStruct object containing all of the values just computed.
     * @throws SurveyException
     */
    public static ClassifiedRespondentsStruct classifyResponses(
            Survey survey,
            List<? extends SurveyResponse> responses,
            Classifier classifier,
            boolean smoothing,
            double alpha)
            throws SurveyException
    {
        double start = System.currentTimeMillis();
        ClassifiedRespondentsStruct classificationStructs = new ClassifiedRespondentsStruct();
        int numValid = 0;
        int numInvalid = 0;
        double validMin = Double.POSITIVE_INFINITY;
        double validMax = Double.NEGATIVE_INFINITY;
        double invalidMin =  Double.POSITIVE_INFINITY;
        double invalidMax = Double.NEGATIVE_INFINITY;

        if (classifier.equals(Classifier.CLUSTER)) {
            clusterResponses(responses, (int) alpha, true);
            for (SurveyResponse sr : responses) {
                classificationStructs.add(
                        new ClassificationStruct(
                                sr,
                                Classifier.CLUSTER,
                                sr.getAllResponses().size(),
                                sr.getScore(),
                                sr.getThreshold(),
                                sr.getComputedValidityStatus().equals(KnownValidityStatus.YES)));
            }
            return classificationStructs;
        } else if (classifier.equals(Classifier.LINEAR)) {
            linearlyClassifyResponses(survey, responses);
        } else if (classifier.equals(Classifier.LPO)) {
            lpoClassification(survey, responses, false, 0.05, 0.5);
            for (SurveyResponse sr : responses) {
                classificationStructs.add(
                        new ClassificationStruct(
                                sr,
                                Classifier.LPO,
                                sr.getAllResponses().size(),
                                sr.getScore(),
                                sr.getThreshold(),
                                sr.getComputedValidityStatus().equals(KnownValidityStatus.YES)));
            }
            return classificationStructs;
        } else if (classifier.equals(Classifier.STACKED)) {
            lpoClassification(survey, responses, false, 0.05, 0.5);
            clusterResponses(responses, (int) alpha, false);
            for (SurveyResponse sr : responses) {
                classificationStructs.add(
                        new ClassificationStruct(
                                sr,
                                Classifier.LPO,
                                sr.getAllResponses().size(),
                                sr.getScore(),
                                sr.getThreshold(),
                                sr.getComputedValidityStatus().equals(KnownValidityStatus.YES)));
            }
            return classificationStructs;
        }

        for (int i = 0; i < responses.size(); i++) {

            if (i % 25 == 0)
                SurveyMan.LOGGER.info(String.format("Classified %d responses (%d valid [%f, %f], %d invalid [%f, %f]) " +
                        "using %s policy."
                        , i, numValid, validMin, validMax, numInvalid, invalidMin, invalidMax, classifier.name()));

            SurveyResponse sr = responses.get(i);
            boolean valid;

            switch (classifier) {
                case ENTROPY:
                    valid = QCMetrics.entropyClassification(survey, sr, responses, smoothing, alpha);
                    if (valid)
                        numValid++;
                    else numInvalid++;
                    break;
                case LOG_LIKELIHOOD:
                    valid = QCMetrics.logLikelihoodClassification(survey, sr, responses, smoothing, alpha);
                    if (valid)
                        numValid++;
                    else numInvalid++;
                    break;
                case ALL:
                    valid = true;
                    numValid++;
                    break;
                default:
                    throw new RuntimeException("Unknown classification policy: " + classifier);
            }

            if (valid) {
                if (validMin > sr.getScore())
                    validMin = sr.getScore();
                if (validMax < sr.getScore())
                    validMax = sr.getScore();
            } else {
                if (invalidMin > sr.getScore())
                    invalidMin = sr.getScore();
                if (invalidMax < sr.getScore())
                    invalidMax = sr.getScore();
            }

            classificationStructs.add(new ClassificationStruct(
                    sr,
                    classifier,
                    sr.getNonCustomResponses().size(),
                    sr.getScore(),
                    sr.getThreshold(),
                    valid)
            );
        }
        double end = System.currentTimeMillis();
        double totalSeconds = (end - start) / 1000;
        double totalMins = totalSeconds / 60;
        SurveyMan.LOGGER.info(String.format("Finished classifying %d responses in %6.0fm%2.0fs",
                responses.size(), totalMins, totalSeconds - (totalMins * 60)));
        return classificationStructs;
    }
}
