import htsjdk.samtools.*;
import htsjdk.variant.variantcontext.*;
import htsjdk.variant.vcf.*;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public class SavvyVcfHomozygosity
{
	// This is the number of variants either side of the current variant that is inspected to calculate homozygosity.
	public static final int BLOCK = 20;
	// This is the maximum number of heterozygous variants allowed in a block of variants for the block to still be homozygous.
	public static final int MAXHET = 7;
	public static final int POT_BLOCK = 35;
	public static final int POT_MAXHET = 2;
	public static final int[] CHR_SIZES = new int[] {249250621, 243199373, 198022430, 191154276, 180915260, 171115067, 159138663, 146364022, 141213431, 135534747, 135006516, 133851895, 115169878, 107349540, 102531392, 90354753, 81195210, 78077248, 59128983, 63025520, 48129895, 51304566};

	/**
	 * Process a sample VCF file to generate a homozygosity mapping. The first argument is a dump of common variants from WGS samples. The second argument is a VCF file to analyse.
	 *
	 * @author Matthew Wakeling
	 */
	public static void main(String[] args) throws IOException, ClassNotFoundException {
		Map<String, Map<Integer, VariantArray>> ref = loadReference(args[0]);
		HardyWeinbergLimit hardy = new HardyWeinbergLimit(ref.values().iterator().next().values().iterator().next().getSamples().length, 0.0001);
		String vcfFile = args[1];
		TreeSet<String> sampleNameList = new TreeSet<String>();
		boolean bases = false;
		boolean combinations = false;
		boolean parents = false;
		for (int i = 2; i < args.length; i++) {
			if ("-b".equals(args[i])) {
				bases = true;
			} else if ("-com".equals(args[i])) {
				combinations = true;
				bases = true;
			} else if ("-p".equals(args[i])) {
				parents = true;
			} else {
				sampleNameList.add(args[i]);
			}
		}
		if (combinations) {
			List<String> files = new ArrayList<String>();
			files.add(vcfFile);
			files.addAll(sampleNameList);
			for (String file : files) {
				VCFFileReader reader = new VCFFileReader(new File(file));
				sampleNameList = new TreeSet<String>(reader.getFileHeader().getGenotypeSamples());
				String[] sampleNames = new String[sampleNameList.size()];
				Iterator<String> iter = sampleNameList.iterator();
				for (int i = 0; i < sampleNames.length; i++) {
					sampleNames[i] = iter.next();
				}
				for (int i = 0; i < sampleNames.length; i++) {
					String[] names = new String[1];
					names[0] = sampleNames[i];
					System.out.print(names[0] + "\t");
					doHomozygosity(file, names, true, false, true, ref, hardy);
					System.out.println("");
					names = new String[2];
					names[0] = sampleNames[i];
					for (int o = i + 1; o < sampleNames.length; o++) {
						names[1] = sampleNames[o];
						System.out.print(names[0] + " " + names[1] + "\t");
						doHomozygosity(file, names, true, false, false, ref, hardy);
						System.out.print("\t");
						doHomozygosity(file, names, true, true, true, ref, hardy);
						System.out.println("");
					}
				}
			}
		} else {
			String[] sampleNames = new String[sampleNameList.size()];
			Iterator<String> iter = sampleNameList.iterator();
			for (int i = 0; i < sampleNames.length; i++) {
				sampleNames[i] = iter.next();
			}
			doHomozygosity(vcfFile, sampleNames, bases, parents, true, ref, hardy);
			System.out.println("");
		}
	}

	public static void doHomozygosity(String vcfFile, String[] sampleNames, boolean bases, boolean parents, boolean printVariantCounts, Map<String, Map<Integer, VariantArray>> ref, HardyWeinbergLimit hardy) {
		int blockSize = (parents ? POT_BLOCK : BLOCK);
		int maxHet = (parents ? POT_MAXHET : MAXHET);
		VCFFileReader reader = new VCFFileReader(new File(vcfFile));
		String[] parentSampleNames = sampleNames;
		if (parents) {
			sampleNames = new String[] {"parents"};
		}
		int oppositeCount = 0;
		int sameCount = 0;
		int[] chrOppositeCount = new int[22];
		int[] chrSameCount = new int[22];
		String currentChr = "";
		SavvyHomozygosity.MultiViterbi viterbi = null;
		for (VariantContext context : reader) {
			String contextChr = context.getChr();
			if (!(currentChr.equals(contextChr))) {
				if (viterbi != null) {
					viterbi.finish();
				}
				currentChr = contextChr;
				viterbi = new SavvyHomozygosity.MultiViterbi(currentChr);
			}
			for (int sampleNo = 0; sampleNo < sampleNames.length; sampleNo++) {
				boolean good = false;
				boolean het = false;
				if (parents) {
					good = true;
					boolean homRef = false;
					Set<String> allAlleles = null;
					Set<String> allAlleles2 = new HashSet<String>();
					for (int parentNo = 0; parentNo < parentSampleNames.length; parentNo++) {
						Genotype g = context.getGenotype(parentSampleNames[parentNo]);
						if (g.isHomRef()) {
							homRef = true;
						}
						Set<String> filters = context.getFilters();
						if ((g.getGQ() > 30) && filters.isEmpty()) {
							String gt = g.getGenotypeString();
							Set<String> alleles = new HashSet<String>();
							int slash = gt.indexOf('/');
							if (slash > 0) {
								alleles.add(gt.substring(0, slash));
								alleles.add(gt.substring(slash + 1));
							}
							alleles.remove(".");
							allAlleles2.addAll(alleles);
							if (!alleles.isEmpty()) {
								if (allAlleles == null) {
									allAlleles = alleles;
								} else {
									allAlleles.retainAll(alleles);
								}
							} else {
								good = false;
							}
						}
					}
					if (good) {
						//System.err.println("allAlleles2: " + allAlleles2);
						if ((allAlleles2.size() == 1) && (!homRef)) {
							het = false;
							if (Pattern.matches("^[0-9]*$", context.getChr())) {
								sameCount++;
								int chrInt = Integer.parseInt(context.getChr());
								chrSameCount[chrInt - 1]++;
							}
							//System.out.print("0");
						} else if ((allAlleles != null) && (allAlleles.isEmpty())) {
							het = true;
							if (Pattern.matches("^[0-9]*$", context.getChr())) {
								oppositeCount++;
								int chrInt = Integer.parseInt(context.getChr());
								chrOppositeCount[chrInt - 1]++;
							}
							//System.out.print("1");
						} else {
							good = false;
						}
					}
				} else {
					Genotype g = context.getGenotype(sampleNames[sampleNo]);
					GenotypeType type = g.getType();
					Map<Integer, VariantArray> chrRef = ref.get(context.getChr());
					if (chrRef != null) {
						VariantArray vArray = chrRef.get(context.getStart());
						if (vArray != null) {
							int[] counts = new int[3];
							for (byte b : vArray.getSamples()) {
								counts[b]++;
							}
							if (hardy.pass(counts[0], counts[1], counts[2])) {
								if (GenotypeType.HET.equals(type)) {
									good = true;
									het = true;
									if (Pattern.matches("^[0-9]*$", context.getChr())) {
										oppositeCount++;
										int chrInt = Integer.parseInt(context.getChr());
										chrOppositeCount[chrInt - 1]++;
									}
									//System.err.println(context.getChr() + "\t" + context.getStart() + "\t0");
								} else if (GenotypeType.HOM_VAR.equals(type)) {
									good = true;
									if (Pattern.matches("^[0-9]*$", context.getChr())) {
										sameCount++;
										int chrInt = Integer.parseInt(context.getChr());
										chrSameCount[chrInt - 1]++;
									}
									//System.err.println(context.getChr() + "\t" + context.getStart() + "\t1");
								}
							}
						}
					}
				}
				if (good) {
					if (het) {
						viterbi.addSignal(new SavvyHomozygosity.ViterbiSignal(context.getStart(), -39.0));
					} else {
						viterbi.addSignal(new SavvyHomozygosity.ViterbiSignal(context.getStart(), 5.0));
					}
				}
			}
		}
		if (viterbi != null) {
			viterbi.finish();
		}
		/*if (bases) {
			if (parents) {
				double wholePercent = (output.getAutozygousBases() / 28810332.86);
				double bestBasePercent = wholePercent;
				int excludeChr = -1;
				for (int i = 0; i < 22; i++) {
					double basePercent = ((output.getAutozygousBases() - output.getChrBases()[i]) * 100.0) / (2881033286L - CHR_SIZES[i]);
					double chrPercent = (output.getChrBases()[i] * 100.0) / CHR_SIZES[i];
					if ((basePercent > bestBasePercent) && (chrPercent < wholePercent - 30.0)) {
						bestBasePercent = basePercent;
						excludeChr = i;
					}
				}
				System.out.print("" + bestBasePercent);
				if (printVariantCounts) {
					System.out.print("\t" + (oppositeCount - (excludeChr >= 0 ? chrOppositeCount[excludeChr] : 0)) + "\t" + (sameCount - (excludeChr >= 0 ? chrSameCount[excludeChr] : 0)));
				}
				if (excludeChr >= 0) {
					System.out.print("\t" + (excludeChr + 1));
				}
			} else {
				System.out.print("" + (output.getAutozygousBases() / 28810332.86));
				if (printVariantCounts) {
					System.out.print("\t" + oppositeCount + "\t" + sameCount);
				}
			}
		}*/
	}

	public static Map<String, Map<Integer, VariantArray>> loadReference(String fileName) throws IOException, ClassNotFoundException {
		ObjectInputStream vcf = new ObjectInputStream(new BufferedInputStream(new FileInputStream(fileName)));
		HashMap<String, Map<Integer, VariantArray>> ref = new HashMap<String, Map<Integer, VariantArray>>();
		boolean hasMoreVariants = true;
		VariantArray vArray = null;
		try {
			vArray = (VariantArray) vcf.readObject();
		} catch (EOFException e) {
			hasMoreVariants = false;
		}
		while (hasMoreVariants) {
			Map<Integer, VariantArray> chrRef = ref.get(vArray.getChr());
			if (chrRef == null) {
				chrRef = new HashMap<Integer, VariantArray>();
				ref.put(vArray.getChr(), chrRef);
			}
			chrRef.put(vArray.getPosition(), vArray);
			try {
				vArray = (VariantArray) vcf.readObject();
			} catch (EOFException e) {
				hasMoreVariants = false;
			}
		}
		return ref;
	}

	public static class HardyWeinbergLimit
	{
		private int samples;
		private int[] lowLimit;
		private int[] highLimit;

		public HardyWeinbergLimit(int samples, double pValue) {
			this.samples = samples;
			this.lowLimit = new int[samples + 1];
			this.highLimit = new int[samples + 1];
			for (int homVar = 0; homVar <= samples; homVar++) {
				while (FishersExactTest.hardyS(samples - homVar - lowLimit[homVar], lowLimit[homVar], homVar) < pValue) {
					lowLimit[homVar]++;
				}
				highLimit[homVar] = samples - homVar;
				while (FishersExactTest.hardyS(samples - homVar - highLimit[homVar], highLimit[homVar], homVar) < pValue) {
					highLimit[homVar]--;
				}
				//System.out.println(samples + "\t" + homVar + "\t" + lowLimit[homVar] + "\t" + highLimit[homVar]);
			}
		}

		public boolean pass(int homRef, int het, int homVar) {
			return (lowLimit[homVar] <= het) && (highLimit[homVar] >= het);
		}
	}
}
