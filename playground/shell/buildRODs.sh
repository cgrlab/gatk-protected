java -Xmx40000m -cp ../java/dist/AnalysisTK.jar org.broadinstitute.sting.atk.PrepareROD REF_FILE_ARG=/seq/references/Homo_sapiens_assembly18/v0/Homo_sapiens_assembly18.fasta ROD_FILE=/seq/references/dbsnp/downloads/snp129_hg18.txt OUT=`echo $1/snp129_hg18.txt.rod` ROD_TYPE=dbSNP ROD_NAME=dbSNP

