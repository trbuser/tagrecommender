package processing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;

import file.PredictionFileWriter;
import file.WikipediaReader;
import file.WikipediaSplitter;
import common.DoubleMapComparator;
import common.UserData;
import common.Utilities;

public class LanguageModelCalculator {

	private final static int REC_LIMIT = 10;
	
	private WikipediaReader reader;
	private double beta;
	private boolean userBased;
	private boolean resBased;
	
	private List<Map<Integer, Integer>> userMaps;
	private List<Double> userDenoms;
	private List<Map<Integer, Integer>> resMaps;
	private List<Double> resDenoms;
	
	public LanguageModelCalculator(WikipediaReader reader, int trainSize, int beta, boolean userBased, boolean resBased) {
		this.reader = reader;
		this.beta = (double)beta / 10.0;
		this.userBased = userBased;
		this.resBased = resBased;
		
		List<UserData> trainList = this.reader.getUserLines().subList(0, trainSize);
		if (this.userBased) {
			this.userMaps = Utilities.getUserMaps(trainList);
			this.userDenoms = getDenoms(this.userMaps);
		}
		if (this.resBased) {
			this.resMaps = Utilities.getResMaps(trainList);
			this.resDenoms = getDenoms(this.resMaps);
		}
	}
	
	private List<Double> getDenoms(List<Map<Integer, Integer>> maps) {
		List<Double> denoms = new ArrayList<Double>();
		for (Map<Integer, Integer> map : maps) {
			double denom = 0.0;
			for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
				denom += Math.pow(Math.E, entry.getValue());
			}
			denoms.add(denom);
		}
		
		return denoms;
	}
	
	// TODO: smoothing param
	public Map<Integer, Double> getRankedTagList(int userID, int resID, boolean sorting, boolean smoothing) {
		//double size = (double)this.reader.getTagAssignmentsCount();
		Map<Integer, Double> resultMap = new LinkedHashMap<Integer, Double>();
		if (this.userBased && this.userMaps != null && userID < this.userMaps.size()) {
			Map<Integer, Integer> userMap = this.userMaps.get(userID);
			for (Map.Entry<Integer, Integer> entry : userMap.entrySet()) {
				double userVal = this.beta * (Math.exp(entry.getValue().doubleValue()) / this.userDenoms.get(userID));
				resultMap.put(entry.getKey(), userVal);
			}
		}
		if (this.resBased && this.resMaps != null && resID < this.resMaps.size()) {
			Map<Integer, Integer> resMap = this.resMaps.get(resID);
			for (Map.Entry<Integer, Integer> entry : resMap.entrySet()) {
				double resVal = (1.0 - this.beta) * (Math.exp(entry.getValue().doubleValue()) / this.resDenoms.get(resID));
				Double val = resultMap.get(entry.getKey());
				resultMap.put(entry.getKey(), val == null ? resVal : val.doubleValue() + resVal);
			}
		}
				
		if (sorting) {
			Map<Integer, Double> sortedResultMap = new TreeMap<Integer, Double>(new DoubleMapComparator(resultMap));
			sortedResultMap.putAll(resultMap);
			
			Map<Integer, Double> returnMap = new LinkedHashMap<Integer, Double>(REC_LIMIT);
			int i = 0;
			for (Map.Entry<Integer, Double> entry : sortedResultMap.entrySet()) {
				if (i++ < REC_LIMIT) {
					returnMap.put(entry.getKey(), entry.getValue());
				} else {
					break;
				}
			}
			return returnMap;
		}
		return resultMap;
	}
	
	//---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
	
	private static String timeString = "";
	
	public static List<Map<Integer, Double>> startLanguageModelCreation(WikipediaReader reader, int sampleSize, boolean sorting, boolean userBased, boolean resBased, int beta, boolean smoothing) {
		timeString = "";
		int size = reader.getUserLines().size();
		int trainSize = size - sampleSize;
		
		Stopwatch timer = new Stopwatch();
		timer.start();
		LanguageModelCalculator calculator = new LanguageModelCalculator(reader, trainSize, beta, userBased, resBased);
		timer.stop();
		long trainingTime = timer.elapsed(TimeUnit.MILLISECONDS);
		List<Map<Integer, Double>> results = new ArrayList<Map<Integer, Double>>();
		if (trainSize == size) {
			trainSize = 0;
		}
		
		timer = new Stopwatch();
		timer.start();
		for (int i = trainSize; i < size; i++) { // the test-set
			UserData data = reader.getUserLines().get(i);
			Map<Integer, Double> map = calculator.getRankedTagList(data.getUserID(), data.getWikiID(), sorting, smoothing);
			results.add(map);
		}
		timer.stop();
		long testTime = timer.elapsed(TimeUnit.MILLISECONDS);
		timeString += ("Full training time: " + trainingTime + "\n");
		timeString += ("Full test time: " + testTime + "\n");
		timeString += ("Average test time: " + testTime / (double)sampleSize) + "\n";
		timeString += ("Total time: " + (trainingTime + testTime) + "\n");
		return results;
	}
	
	public static void predictSample(String filename, int trainSize, int sampleSize, boolean userBased, boolean resBased, int beta) {
		//filename += "_res";

		WikipediaReader reader = new WikipediaReader(trainSize, false);
		reader.readFile(filename);

		List<Map<Integer, Double>> modelValues = startLanguageModelCreation(reader, sampleSize, true, userBased, resBased, beta, true);
		
		List<int[]> predictionValues = new ArrayList<int[]>();
		for (int i = 0; i < modelValues.size(); i++) {
			Map<Integer, Double> modelVal = modelValues.get(i);
			predictionValues.add(Ints.toArray(modelVal.keySet()));
		}
		String suffix = "_mp_ur_";
		if (!userBased) {
			suffix = "_mp_r_";
		} else if (!resBased) {
			suffix = "_mp_u_";
		}
		reader.setUserLines(reader.getUserLines().subList(trainSize, reader.getUserLines().size()));
		PredictionFileWriter writer = new PredictionFileWriter(reader, predictionValues);
		String outputFile = filename + suffix + beta;
		writer.writeFile(outputFile);
		
		Utilities.writeStringToFile("./data/metrics/" + outputFile + "_TIME.txt", timeString);
	}
}
