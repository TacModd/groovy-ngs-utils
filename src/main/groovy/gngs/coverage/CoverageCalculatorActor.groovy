/*
 *  Groovy NGS Utils - Some simple utilites for processing Next Generation Sequencing data.
 *
 *  Copyright (C) 2018 Simon Sadedin, ssadedin<at>gmail.com
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package gngs.coverage

import java.util.concurrent.atomic.AtomicInteger

import gngs.*
import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.util.logging.Log
import groovyx.gpars.actor.Actor
import groovyx.gpars.actor.DefaultActor
import htsjdk.samtools.SAMRecord
import htsjdk.samtools.SAMRecordIterator
import htsjdk.tribble.readers.TabixReader

/**
 * The count of reads overlapping a base at a position for a sample
 * 
 * @author Simon Sadedin
 */
@CompileStatic
@ToString
class SampleReadCount {
    
    final Region target
    
    final String chr
    
    final int pos 
    
    final int reads
    
    final String sample

    public SampleReadCount(Region target, String chr, int pos, int reads, String sample) {
        super();
        this.target = target;
        this.chr = chr;
        this.pos = pos;
        this.reads = reads;
        this.sample = sample;
    }    
}


/**
 * An actor that accepts coordinate ordered reads and transforms them into
 * the coverage (number of overlapping reads at a position) over a set of 
 * pre-defined regions. The coverage values are emitted as messages to 
 * a downstream consumer as SampleReadCount objects, one for each position
 * in the target range.
 * 
 * @author Simon Sadedin
 */
@Log
class CoverageCalculatorActor extends RegulatingActor<ReadRange> {
    
    AtomicInteger pending = new AtomicInteger(0)
    
    String currentChr
    
//    List<int[]> reads = new LinkedList()
    
    final gngs.OverlapTracker reads = new gngs.OverlapTracker()
    
    int pos
    
    Region currentRegion
    
    int currentReferenceIndex = -1
    
    // If set, all reads should be ignored until a non-matching read occurs
    int flushingReferenceIndex = -1
    
    Regions targetRegions
    
    Iterator<Region> regionIter
    
    String sample
    
    Set<Integer> bedReferenceIndexes
    
    List<String> bamContigs
    
    // for debug only
    ReadRange currentRead
    
    /**
     * If true, fragments will be counted rather than reads
     * <p>
     * Counting reads over-estimates the biological sampling, but is concordant with 
     * other tools such as BEDTools etc.
     */
    boolean countFragments = true
    
    ProgressCounter progress = new ProgressCounter(
        withRate:true, 
        extra:  {"region: $currentRegion, sample: $sample, readBuffer: ${reads.size()}, pending: $pending"},
        log: log,
        timeInterval: 10000,
        lineInterval: 500
        )

    
    public CoverageCalculatorActor(SAM bam, Regions targetRegions, Actor combiner, String sample, boolean countFragments = true) {
        this(bam.getContigList(), targetRegions, combiner, sample, countFragments)
    }
    
    public CoverageCalculatorActor(List<String> allContigs, Regions targetRegions, Actor combiner, String sample, boolean countFragments) {
        super(combiner, 20000, 100000);
        this.targetRegions = targetRegions
        this.regionIter = targetRegions.iterator();
        this.sample = sample;
        this.countFragments = countFragments;
        
        this.bamContigs = allContigs
        this.bedReferenceIndexes = targetRegions*.chr.unique().collect { String chr ->
            int bamContigIndex = bamContigs.indexOf(chr)
            if(bamContigIndex<0)
                throw new IllegalArgumentException("The BED file you provided contains reference to contig $chr which is not present in the BAM file contigs")
            return bamContigIndex
        } as Set
        
        log.info "Calculating coverage for ${targetRegions.numberOfRanges} regions"
        
        nextRegion()
    }
    

    void onEnd() {
        log.info "Flushing coverage actor"
        this.flushToEnd()
    }
    
    @CompileStatic
    void process(ReadRange r) {
        
//        Thread.sleep(1000)
        
        if(r.referenceIndex == this.flushingReferenceIndex)
            return
            
        if(currentRegion == null) // occurs if we ran out of regions => end
            return
        
        // Assumption: reads in BAM file 
        // are sorted the same as in the BED file
        this.currentRead = r
        assert r != null
        assert currentRegion != null
        
        while(r.referenceIndex != currentReferenceIndex) {
            
            if(!bedReferenceIndexes.contains(r.referenceIndex)) {
                return
            }
            
            final int currentTo = currentRegion.to
            while(pos < currentTo) {
                ++pos
                flushPosition()
            }
            
            if(regionIter.hasNext()) {
                if(!nextRegion()) {
                    return
                }
                log.info "Transition to ${currentRegion} seeking read ${r}"
            }
        }
        
        flushToReadPosition(r)
        
        reads.add(r)
    }
    
    @CompileStatic
    private void flushToEnd() {
        if(currentRegion == null)
            return
            
        while(currentRegion != null) {
            final int regionEnd = currentRegion.to
            while(pos < regionEnd) {
                ++pos
                flushPosition()
            }
            
            nextRegion()
        }
    }
    
    @CompileStatic
    private void flushToReadPosition(final ReadRange r) {
        final int alignmentStart = r.alignmentStart
        int regionEnd = currentRegion.to
        final int startPos = pos
        while(pos < alignmentStart) {
            ++pos
            if(pos > regionEnd) {
                final int transitionPos = pos
                nextRegion()
                
                // If switching to the next region caused us to hit a new 
                // chr then we have finished and can ignore this read
                // Note also that running out of regions causes a change in
                // currentReferenceIndex so that too will terminate here
                if(r.referenceIndex != currentReferenceIndex) {
                    log.info "Transition to ${currentRegion} due to read $r (started at $startPos, hit $transitionPos in scan)"
                    this.flushingReferenceIndex = r.referenceIndex 
                    return
                }
                    
                regionEnd = currentRegion.to
            }
            else
                flushPosition()
        }
    }
    
    /**
     * Transition to the next target region, taking into account that it might 
     * also cause transition to next reference sequence (chromosome).
     * 
     * @return  true iff a new region was set, false if regions are exhausted
     */
    @CompileStatic
    boolean nextRegion() {
       Region oldRegion = currentRegion
       
       if(!regionIter.hasNext()) {
           currentRegion = null
           currentReferenceIndex = -1
           return false
       }
           
       currentRegion = regionIter.next() 
       currentReferenceIndex = this.bamContigs.indexOf(currentRegion.chr)
       
       if(currentReferenceIndex < 0)
           throw new IllegalStateException("BED file sequence ${currentRegion.chr} is not found in BAM file sequences")
           
       pos = currentRegion.from
       if((oldRegion != null) && currentRegion.chr != oldRegion.chr) {
           log.info "Processing $currentRegion.chr"
           this.reads.clear()
       }
       else {
           dropNonOverlapping()
       }
       return true
    }
    
    @CompileStatic
    private void flushPosition() {
        dropNonOverlapping()
        SampleReadCount src = new SampleReadCount(currentRegion, currentRegion.chr, pos, reads.size(), sample)
        sendDownstream(src)
    }
    
    @CompileStatic
    void dropNonOverlapping() {
        this.reads.removeNonOverlaps(pos)
    }
    
    @CompileStatic
    void iteratorRemove(Iterator i) {
        i.remove()
    }
    
    
    String toString() {
        "CoverageCalculator(sample=$sample, $currentRegion, pos=$pos)"
    }
    
    @CompileStatic
    static void processBAM(SAM bam, Regions scanRegions, RegulatingActor downstream, final int minMQ, String sample=null, boolean countFragments = true) {
       
        AtomicInteger downstreamCount = new AtomicInteger(0)
        
        if(sample == null)
            sample = bam.samples[0]
         
        CoverageCalculatorActor calculator = new CoverageCalculatorActor(bam, scanRegions, downstream, sample, countFragments)
        calculator.start()
        
        List<String> chrs = (List<String>)scanRegions.collect { Region r -> r.chr }.unique()
        int failMQ = 0
        for(String chr in chrs) {
            int start = Math.max(0, scanRegions.index[chr].ranges.firstKey() - 1000)
            int end = scanRegions.index[chr].ranges.lastKey() + 1000
            log.info "Scan $chr from $start to $end"
            bam.withIterator(new Region(chr, start, end))  { SAMRecordIterator iter ->
                while(iter.hasNext()) {
                    SAMRecord r = iter.next()
                    if(r.getMappingQuality()>=minMQ)
                        calculator.send(new AcknowledgeableMessage(new ReadRange(r, countFragments), downstreamCount))
                    else
                        ++failMQ
                } 
            }
        }
        log.info "Sending stop message to CRA ${bam.samples[0]} ..."
        calculator << RegulatingActor.STOP
        calculator.join()
        if(failMQ) {
            log.info "${failMQ} reads failed mapping quality threshold $minMQ in $sample"
        }
    }
}
