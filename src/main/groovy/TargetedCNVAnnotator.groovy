

class TargetedCNVAnnotator {
    
    Regions target
    
    RangedData dgv 
    
    String omimFile
    
    /**
     * Columns from schema of DGV in UCSC table
     */
    List DGV_COLUMNS = ["bin", "chrom", "chromStart", "chromEnd", "name", "score", "strand", "thickStart", "thickEnd", "itemRgb", "varType", "reference", "pubMedId", "method", "platform", "mergedVariants", "supportingVariants", "sampleSize", "observedGains", "observedLosses", "cohortDescription", "genes", "samples"]
    
    public TargetedCNVAnnotator(Regions targetRegions, RangedData dgv/*, String omimFile*/) {
        this.target = targetRegions
        this.dgv = dgv
    }
    
    public TargetedCNVAnnotator(Regions targetRegions, String dgvFile/*, String omimFile*/) {
        this.target = targetRegions
        this.dgv = Utils.time("Loading DGV data ...") { new RangedData(dgvFile, 1,2,3).load(columnNames:DGV_COLUMNS) }
//        this.omimFile = omimFile
    }
    
   void annotate(String vcfFile, Writer output) {
       VCF.filter(vcfFile) { Variant v ->
           
           // Output non-CNV variants unchanged
           if(!["GAIN","LOSS"].contains(v.type)) {
               return true   
           }
           
           List<GRange> compatibleRanges = findCompatibleRanges(v)
           
           // These GRanges have Regions as extra's:
           List<Region> dgvCnvs = compatibleRanges*.extra
           
           // They must be the same type
           dgvCnvs = filterByType(v, dgvCnvs)
           
           // If we found one or more plausible candidates, add them in
           v.update("CNV Known Variant Annotation") {
               v.info.KCNV = dgvCnvs.collect { cnv ->
                   int cnvCount = v.type == "GAIN" ? cnv.observedGains : cnv.observedLosses
                   [cnv.name, cnvCount, (float)cnvCount / cnv.sampleSize.toFloat()]
               }.join(",")
           }
           
           // Look for "crossing" variants
           List<Region> spanning = dgv.getOverlaps(v.chr, v.pos, v.pos+v.size()).grep { GRange r ->
               r.spans(v.range)  
           }*.extra
           
           spanning = filterByType(v, spanning)
           
           float spanningFreq = spanning.grep { it.sampleSize>5}.collect { cnv ->
               int cnvCount = v.type == "GAIN" ? cnv.observedGains : cnv.observedLosses
               (float)cnvCount / cnv.sampleSize.toFloat()
           }.max()?:0.0f
           
           v.update("CNV Known Variant Annotation") {
               if(dgvCnvs) {
                   v.info.KCNV = dgvCnvs.collect { cnv ->
                       int cnvCount = v.type == "GAIN" ? cnv.observedGains : cnv.observedLosses
                       [cnv.name, cnvCount, (float)cnvCount / cnv.sampleSize.toFloat()]
                   }.join(",")
               }
               v.info.SCNV = spanning.size() + ":" + String.format("%.3f",spanningFreq)
           }
           
           System.err.println "Found ${dgvCnvs.size()} plausible known DGV variants for ${v}, and ${spanning.size()} spanning CNVs (max freq = $spanningFreq)" 
           
           return true
       }
   }
   
   List<Region> filterByType(Variant v, List<Region> regions) {
       if(v.type == "LOSS")
          return regions.grep { it.varType in ["Loss","Gain+Loss"] }
       else
          return regions.grep { it.varType in ["Gain","Gain+Loss"] }
   }
   
   List<GRange> findCompatibleRanges(Variant v) {
       
       // First determine the true possible range of the CNV
       // That means, from the first upstream unaffected target region 
       // through to the first downstream unaffected target region
       Region maxRange = computeMaxRange(v)
           
       // For now we consider the region in the VCF to represet exactly
       // the minimum range ... however we could take into account
       // one day the "soft" boundaries for an imprecise CNV call and put
       // some margins here
       Region minRange = new Region(v.chr, v.pos..(v.pos+v.size()))
           
       // Find in DGV any CNVs that *start* inside the required region
       def compatibleRanges = dgv.getOverlaps(minRange).grep { IntRange r ->
           boolean result = (r.from > maxRange.from) && (r.from < minRange.from) &&
               (r.to > minRange.to) && (r.to < maxRange.to)
//           println "Range $r.from-$r.to vs ($maxRange.from,$minRange.from)-($minRange.to,$maxRange.to) : $result"
           return result
       }
       
       return compatibleRanges
   }
   
   Region computeMaxRange(v) {
       // Need the first upstream target region
       IntRange prevRange = target.previousRange(v.chr, v.pos-1)
       IntRange nextRange = target.nextRange(v.chr, v.pos + v.size())
       
       if(prevRange == null)
           prevRange = 1..1
           
       if(nextRange == null)
           nextRange = Integer.MAX_VALUE..Integer.MAX_VALUE
       
       return new Region(v.chr, (prevRange.to+1)..nextRange.from)
   }
    
    public static void main(String [] args) {
        
        Cli cli = new Cli(header: "CNV Annotator for Targeted Sequencing")
        cli.with {
            vcf "VCF to annotate", args:1, required:true
            bed "BED file of regions sequenced", args:1, required:true
            dgv "DGV file from UCSC (dgvMerged)", args:1, required:true
//            omim "OMIM genes file", args: 1, required:true
//            o "Output file", args:1, required:true
        }
        
        def opts = cli.parse(args)
        if(!opts)
            System.exit(1)
            
         
        BED targetRegions = Utils.time("Reading target regions ...") { new BED(opts.bed).load() }
        new TargetedCNVAnnotator(targetRegions, opts.dgv).annotate(opts.vcf, null)
    }

}
