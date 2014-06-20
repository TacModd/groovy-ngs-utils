groovy-ngs-utils
================

A collection of utilities for working with next generation (MPS) sequencing data in Groovy

This is a collection of Groovy wrappers and interfaces that make it easy to perform 
scripting operations with Groovy to process NGS data.

The quality of this code is very alpha - it's simply what I've created as part of my PhD as I
do various data handling tasks. The kind of operations currently supported are:

  * Reading, processing and filtering VCF files, including integration with common annotation sources such as VEP, Annovar and SnpEFF
  * Reading, processing and filtering BED files or any source of genomic ranges
  * Reading, processing and performing logical operations with pedigree (PED) files and family structures
  * Working with SAM files (particularly, generating and working with Pileups)
  * Predicting Restriction Enzyme cut sites
  * A range of statistical operations including R-like data frames and linear modeling constructs

Since these utilities are optimized for scripting, they live in the default Java package. This means you can 
easily write command line scripts, such as:

  cat my.vcf | groovy -e 'VCF.filter { it.qual > 20 && it.info.DP.toInteger()>5 }' > filtered.vcf

  cat my.bam | groovy -e 'SAM.eachRead { if(it.mappingQuality == 0) { println it.readName } }'
  
  coverageBed -d  -abam test.bam -b test.bed | cut -f 6 | 'Stats.read().median'
  
These functions are all built upon Samtools, Picard Tools, BioJava and Apache commons-math. The jar file that 
is built bundles all the necessary libraries so that you can easily include them all with just one
classpath entry (or put it into your .groovy/lib).

Careful attention has been paid to make, wherever possible, operations operate on streaming data so that
memory is not a bottleneck in manipulating large data sets.
