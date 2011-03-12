package org.broadinstitute.sting.playground.gatk.walkers.variantrecalibration;

import org.apache.log4j.Logger;
import org.broadinstitute.sting.utils.exceptions.UserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: rpoplin
 * Date: Mar 10, 2011
 */

public class TrancheManager {

    protected final static Logger logger = Logger.getLogger(TrancheManager.class);

    // ---------------------------------------------------------------------------------------------------------
    //
    // Code to determine FDR tranches for VariantDatum[]
    //
    // ---------------------------------------------------------------------------------------------------------

    public static abstract class SelectionMetric {
        String name = null;

        public SelectionMetric(String name) {
            this.name = name;
        }

        public String getName() { return name; }

        public abstract double getThreshold(double tranche);
        public abstract double getTarget();
        public abstract void calculateRunningMetric(List<VariantDatum> data);
        public abstract double getRunningMetric(int i);
        public abstract int datumValue(VariantDatum d);
    }

    public static class NovelTiTvMetric extends SelectionMetric {
        double[] runningTiTv;
        double targetTiTv = 0;

        public NovelTiTvMetric(double target) {
            super("NovelTiTv");
            targetTiTv = target; // compute the desired TiTv
        }

        public double getThreshold(double tranche) {
            return fdrToTiTv(tranche, targetTiTv);
        }

        public double getTarget() { return targetTiTv; }

        public void calculateRunningMetric(List<VariantDatum> data) {
            int ti = 0, tv = 0;
            runningTiTv = new double[data.size()];

            for ( int i = data.size() - 1; i >= 0; i-- ) {
                VariantDatum datum = data.get(i);
                if ( ! datum.isKnown ) {
                    if ( datum.isTransition ) { ti++; } else { tv++; }
                    runningTiTv[i] = ti / Math.max(1.0 * tv, 1.0);
                }
            }
        }

        public double getRunningMetric(int i) {
            return runningTiTv[i];
        }

        public int datumValue(VariantDatum d) {
            return d.isTransition ? 1 : 0;
        }
    }

    public static class TruthSensitivityMetric extends SelectionMetric {
        double[] runningSensitivity;
        double targetTiTv = 0;
        int nTrueSites = 0;

        public TruthSensitivityMetric(int nTrueSites) {
            super("TruthSensitivity");
            this.nTrueSites = nTrueSites;
        }

        public double getThreshold(double tranche) {
            return tranche/100; // tranche of 1 => 99% sensitivity target
        }

        public double getTarget() { return 1.0; }

        public void calculateRunningMetric(List<VariantDatum> data) {
            int nCalledAtTruth = 0;
            runningSensitivity = new double[data.size()];

            for ( int i = data.size() - 1; i >= 0; i-- ) {
                VariantDatum datum = data.get(i);
                nCalledAtTruth += datum.atTruthSite ? 1 : 0;
                runningSensitivity[i] = 1 - nCalledAtTruth / (1.0 * nTrueSites);
            }
        }

        public double getRunningMetric(int i) {
            return runningSensitivity[i];
        }

        public int datumValue(VariantDatum d) {
            return d.atTruthSite ? 1 : 0;
        }
    }

    public static List<Tranche> findTranches( ArrayList<VariantDatum> data, final double[] tranches, SelectionMetric metric ) {
        return findTranches( data, tranches, metric, null );
    }

    public static List<Tranche> findTranches( ArrayList<VariantDatum> data, final double[] trancheThresholds, SelectionMetric metric, File debugFile ) {
        logger.info(String.format("Finding %d tranches for %d variants", trancheThresholds.length, data.size()));

        List<VariantDatum> tranchesData = sortVariantsbyLod(data);
        metric.calculateRunningMetric(tranchesData);

        if ( debugFile != null) { writeTranchesDebuggingInfo(debugFile, tranchesData, metric); }

        List<Tranche> tranches = new ArrayList<Tranche>();
        for ( double trancheThreshold : trancheThresholds ) {
            Tranche t = findTranche(tranchesData, metric, trancheThreshold);

            if ( t == null ) {
                if ( tranches.size() == 0 )
                    throw new UserException(String.format("Couldn't find any tranche containing variants with a %s > %.2f", metric.getName(), metric.getThreshold(trancheThreshold)));
                break;
            }

            tranches.add(t);
        }

        return tranches;
    }

    private static void writeTranchesDebuggingInfo(File f, List<VariantDatum> tranchesData, SelectionMetric metric ) {
        try {
            PrintStream out = new PrintStream(f);
            out.println("Qual metricValue runningValue");
            for ( int i = 0; i < tranchesData.size(); i++ ) {
                VariantDatum  d = tranchesData.get(i);
                int score = metric.datumValue(d);
                double runningValue = metric.getRunningMetric(i);
                out.printf("%.4f %d %.4f%n", d.lod, score, runningValue);
            }
        } catch (FileNotFoundException e) {
            throw new UserException.CouldNotCreateOutputFile(f, e);
        }
    }

    private static List<VariantDatum> sortVariantsbyLod(final ArrayList<VariantDatum> data) {
        Collections.sort(data);
        return data;
    }

    public static Tranche findTranche( final List<VariantDatum> data, final SelectionMetric metric, final double trancheThreshold ) {
        logger.info(String.format("  Tranche threshold %.2f => selection metric threshold %.3f", trancheThreshold, metric.getThreshold(trancheThreshold)));

        double metricThreshold = metric.getThreshold(trancheThreshold);
        int n = data.size();
        for ( int i = 0; i < n; i++ ) {
            if ( metric.getRunningMetric(i) >= metricThreshold ) {
                // we've found the largest group of variants with Ti/Tv >= our target titv
                Tranche t = trancheOfVariants(data, i, trancheThreshold, metric.getTarget());
                logger.info(String.format("  Found tranche for %.3f: %.3f threshold starting with variant %d; running score is %.3f ",
                        trancheThreshold, metricThreshold, i, metric.getRunningMetric(i)));
                logger.info(String.format("  Tranche is %s", t));
                return t;
            }
        }

        return null;
    }

    public static Tranche trancheOfVariants( final List<VariantDatum> data, int minI, double fdr, double target ) {
        int numKnown = 0, numNovel = 0, knownTi = 0, knownTv = 0, novelTi = 0, novelTv = 0;

        double minLod = data.get(minI).lod;
        for ( VariantDatum datum : data ) {
            if ( datum.lod >= minLod ) {
                //if( ! datum.isKnown ) System.out.println(datum.pos);
                if ( datum.isKnown ) {
                    numKnown++;
                    if ( datum.isTransition ) { knownTi++; } else { knownTv++; }
                } else {
                    numNovel++;
                    if ( datum.isTransition ) { novelTi++; } else { novelTv++; }

                }
            }
        }

        double knownTiTv = knownTi / Math.max(1.0 * knownTv, 1.0);
        double novelTiTv = novelTi / Math.max(1.0 * novelTv, 1.0);

        int accessibleTruthSites = countCallsAtTruth(data, Double.NEGATIVE_INFINITY);
        int nCallsAtTruth = countCallsAtTruth(data, minLod);

        return new Tranche(fdr, target, minLod, numKnown, knownTiTv, numNovel, novelTiTv, accessibleTruthSites, nCallsAtTruth);
    }

    public static double fdrToTiTv(double desiredFDR, double targetTiTv) {
            return (1.0 - desiredFDR / 100.0) * (targetTiTv - 0.5) + 0.5;
    }

    public static int countCallsAtTruth(final List<VariantDatum> data, double minLOD ) {
        int n = 0;
        for ( VariantDatum d : data) { n += d.atTruthSite && d.lod >= minLOD ? 1 : 0; }
        return n;
    }

}