/*
 *  Groovy NGS Utils - Some simple utilites for processing Next Generation Sequencing data.
 *
 *  Copyright (C) 2013 Simon Sadedin, ssadedin<at>gmail.com
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
import groovy.transform.CompileStatic;

import java.awt.event.ItemEvent;
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel.MapMode

import org.broad.tribble.index.Block
import org.broad.tribble.index.Index
import org.broad.tribble.index.IndexFactory
import org.codehaus.groovy.runtime.StackTraceUtils


/**
 * The VCF class supports simple parsing, filtering, querying and updating of
 * VCF files.
 * <p>
 * The primary method is the {@link #parse(Closure)} method which can be
 * optionally passed a file name, stream, or if left out, will parse
 * from standard input. When a Closure is supplied, it can be used for
 * filtering and updating the VCF file as it is read. Each record is passed
 * to the closure as it is read, and if false is returned
 * from the closure it will be discarded. Various forms of information can
 * be queried - the INFO section is parsed to a map and genotype information
 * is available as well as computed dosage for each variant (0,1,2). Support
 * for SnpEff annotations is also provided with a dedicated snpEffInfo property
 * that returns a list of {@link SnpEffInfo} objects describing the various
 * annotations available.
 * <p>
 * Most information is lazily computed so the cost of parsing complex fields is
 * deferred unless they are asked for.
 * <p>
 * VCF variants can be updated by entering an update closure:
 * <pre>
 * v.update { v.info.FOO='bar' }
 * </pre>
 * <p>
 * Limited support for "Pedigrees" is available. At the moment only grouping of
 * samples into families is supported and intra-family relationships are not modeled.
 * <p>
 * The Iterable interface and "in" operator are supported so that you can do common
 * iteration and membership tests:
 * <p>
 * <code>
 * for(Variant v in vcf) { 
 *   if(v in vcf2) { ... } 
 * }
 * </code>
 * <p>
 * Note that you can avoid excessive memory usage by streaming a VCF into the
 * {@link #parse(Closure)} or {@link #filter(Closure)} methods. However if your
 * desire is to lookup a specific region in a very large VCF file, you should
 * index the VCF file and use the {@link VCFIndex} class to query the region
 * specifically.
 *
 * @author simon.sadedin@mcri.edu.au
 */
class VCF implements Iterable<Variant> {
    
    List<String> headerLines = []
    
    String [] lastHeaderLine
    
    List<String> samples
    
    List<Variant> variants = []
    
    List<Pedigree> pedigrees
    
    List<Pedigree> samplePedigrees
    
    Map<String,List<Variant>> chrPosIndex = [:]
    
    String fileName
    
    /**
     * Cached list of VEP columns, when they exist in the VCF file.
     * Lazily populated when getVepColumns() is called.
     */
    private String [] vepColumns = null
    
    /**
     * Lazily populated when getInfoMetaData is called
     */
    private Map<String, Map> infoMetaDatas
    
    VCF() {
    }
    
    /**
     * Creates an empty VCF file based on the header of the given VCF file
     * 
     * @param fileName
     */
    VCF(String fileName) {
         this.fileName = fileName
         
         new File(fileName).withReader { Reader r ->
           String line = r.readLine();
           while(line != null) {
             if(line.startsWith('#')) {
                 this.headerLines.add(line)
             }
             else
                 break
                 
             line = r.readLine()
           }
         }
         parseLastHeaderLine()
    }
    
    /**
     * Return a list of variants starting at the given location
     *
     * @param chr
     * @param pos
     * @return
     */
    @CompileStatic
    List variantsAt(String chr, int pos) {
        chrPosIndex[chr+":"+pos]
    }
    
    
    static VCF parse(String fileName, Pedigrees peds, Closure c = null) {
        parse(fileName,peds.families.values() as List, c)
    }
    
    /**
     * Convenience method to accept string for parsing file
     */
    static VCF parse(String fileName, List<Pedigree> peds = null, Closure c = null) {
        if(fileName == "-")
          parse(System.in,peds,c)
        else
          parse(new File(fileName),peds,c)
    }
    
    static VCF parse(String fileName, Closure c) {
        parse(fileName,null,c)
    }
    
    static VCF parse(File f, List<Pedigree> peds = null, Closure c = null) {
        parse(new BufferedInputStream(new FileInputStream(f)),peds,c)
    }
    
    static VCF parse(Closure c = null) {
        parse("-",null,c)
    }
    
    static VCF parse(List<Pedigree> peds, Closure c = null) {
        parse("-",peds,c)
    }
    
    static VCF parse(BufferedInputStream f, List<Pedigree> peds = null, Closure c = null) {
        parseImpl(f,false, peds,c)
    }
    
    static VCF filter(File f, Closure c = null) {
        filter(f,null,c)
    }
    
    static VCF filter(File f, List<Pedigree> peds, Closure c = null) {
        filter(new BufferedInputStream(new FileInputStream(f)),peds,c)
    }
    
    static VCF filter(String fileName, Closure c = null) {
        filter(fileName,null,c)
    }
    
    static VCF filter(String fileName, List<Pedigree> peds, Closure c = null) {
        if(fileName == "-")
          filter(System.in,peds,c)
        else
          filter(new File(fileName),peds,c)
    }
    
    static VCF filter(Closure c = null) {
        filter("-",null,c)
    }
    
    static VCF filter(BufferedInputStream f, List<Pedigree> peds = null, Closure c = null) {
        parseImpl(f,true,peds,c)
    }
    
    /**
     * Parse the given VCF file, returning a full VCF, optionally filtered
     * by the closure (only rows returning true from closure will be included)
     *
     * @param f file to parse
     * @param peds List of pedigree objects describing sample - family relationships
     * @param c filter closure
     */
//    @CompileStatic
    private static VCF parseImpl(BufferedInputStream f, boolean filterMode, List<Pedigree> peds, Closure c) {
        VCF vcf = new VCF(pedigrees:peds)
        String lastHeaderLine
        long lastPrintTimeMs = System.currentTimeMillis()
        int count = 0
        boolean flushedHeader = false
        Map chrPosIndex = vcf.chrPosIndex
        ProgressCounter progress = new ProgressCounter(withTime:true)
        f.eachLine { String line ->
            
            ++count
            
            progress.count()
           
            if(line.startsWith('#')) {
                vcf.headerLines.add(line)
                return
            }
            
            if(vcf.lastHeaderLine == null) {
                vcf.parseLastHeaderLine()
            }
            Variant v = Variant.parse(line)
            v.header = vcf
            try {
              if(!c || !(c(v)==false)) {
                  if(filterMode) {
                      if(!flushedHeader)  {
                          vcf.printHeader()
                          flushedHeader = true
                      }
                      println v.line
                  }
                  else {
                      vcf.add(v)
                  }
            }
          }
          catch(Exception e) {
             throw new RuntimeException("Procesing failed at line $count :\n" + line, e)
          }
        }
        
        if(filterMode && !flushedHeader)  {
            vcf.printHeader()
            flushedHeader = true
        }
        return vcf
    }
    
    /**
     * Add the given variant to this VCF
     */
    VCF add(Variant v) {
        variants << v
        String key = v.chr+":"+v.pos
        List chrPosVars = chrPosIndex[key]
        if(!chrPosVars)
            chrPosIndex[key] = [v]
        else
            chrPosVars.add(v)
        return this
    }
    
    Pedigree findPedigreeBySampleIndex(int i) {
        def peds = getPedigrees()
         if(samplePedigrees == null) {
             samplePedigrees = []
             samples.eachWithIndex { String s, int index ->
                 samplePedigrees[index] =  peds.find { p -> p.individuals.any { it.id == s } }
             }
         }
         return samplePedigrees[i]
    }

    @CompileStatic
    int sampleIndex(String sampleName) {
        return samples.indexOf(sampleName)
    }
    
    /**
     * Extract sample names and other info from the final header line in the VCF file
     */
    void parseLastHeaderLine() {
        this.lastHeaderLine = this.headerLines[-1].split('[\t ]{1,}')
        this.samples = []
        for(int i=9; i<this.lastHeaderLine.size(); ++i) {
            this.samples.add(this.lastHeaderLine[i])
        }
    }
    
    
    @CompileStatic
    Map<String,Object> getInfoMetaData(String id) {
        if(this.infoMetaDatas == null) {
          this.infoMetaDatas = 
              this.headerLines.grep { String hline -> hline.startsWith("##INFO") }.collect { String hline -> parseInfoMetaData(hline) }.collectEntries { 
                  Map hEntry -> [ hEntry.ID, hEntry ] 
              }
        }
        return this.infoMetaDatas[id]
    }
    
    /**
     * Add a header for describing an info value to be added to a 
     * VCF. 
     * 
     * @param id
     * @param desc
     * @param value A prototype value to infer the type of value for the INFO field from.
     */
    void addInfoHeader(String id, String desc, Object value) {
        int lastInfo = this.headerLines.findLastIndexOf { it.startsWith("##INFO=") }
        if(lastInfo < 0)
            lastInfo = 1
                    
        String valueType = "String"
        if(value instanceof Integer) {
            valueType = "Integer"
        }
        else
        if(value instanceof Float) {
            valueType = "Float"
        }
        else
        if(value instanceof Double) {
            valueType = "Double"
        }
                     
        this.headerLines = this.headerLines[0..lastInfo] + 
            ["##INFO=<ID=$id,Number=1,Type=${valueType},Description=\"$desc\">"] +
            this.headerLines[(lastInfo+1)..-1]
                        
        // Clear any cached meta data so it will get reparsed
        this.infoMetaDatas = null;
    }
    
    /**
     * Return true if this VCF file contains the specified INFO tags
     * <p>
     * Note: it only checks if the tag is described in the header,
     * not whether any variant in the VCF actually has the INFO tag.
     * You still need to account that any given record may be missing the
     * tag.
     * 
     * @param id    id of INFO tag to check for
     * @return  true iff an INFO meta data line of the VCF file describes this tag
     */
    @CompileStatic
    boolean hasInfo(String id) {
        return getInfoMetaData(id) != null
    }
    
    /**
     * Dedicated support for dynamically returning the VEP columns present in this VCF file
     * 
     * @return
     */
    String[] getVepColumns() {
        
        if(vepColumns != null)
            return vepColumns
        
        Map csqInfo = getInfoMetaData("CSQ")
        if(csqInfo==null) 
            throw new IllegalArgumentException("VCF does not contain VEP annotations")
            
        String fields = (csqInfo.Description =~ 'Format: (.*)')[0][1]
        
        vepColumns = fields.split("\\|")
        return vepColumns
    }
    
    /**
     * Parse a VCF INFO meta data line and return the values as a Map
     * 
     * @param info
     * @return  map containing entries: ID, Number, Type, Description
     */
    Map<String, Object> parseInfoMetaData(String info) {
        
        String contents = (info =~ /##INFO=<(.*)>/)[0][1]
            
        // remove description
        String description = contents.substring(contents.indexOf('Description='))
            
        Map metaFields = contents.substring(0,contents.indexOf('Description=')).split(",").collectEntries {
                                [it.split("=")[0], it.split("=")[1]] }
        
        metaFields.Description = (description =~ /"(.*)"/)[0][1]
            
        return metaFields
    }
    
    BED toBED() {
        BED bed = new BED()
        for(Variant v in this) {
          bed.add(v.chr, v.pos, v.pos + v.size())
        }
        return bed
    }
    
    boolean isCase(Object obj) {
        if(obj instanceof Variant) {
            Variant v = obj
            
            // Note that it's "in" there if any of the ALT's match, we don't require all of them
            return this.chrPosIndex.containsKey(v.chr + ':' + v.pos) && chrPosIndex[v.chr+':'+v.pos].any { 
                it.ref == v.ref && it.alts.any { it == v.alt }
            }
        }
    }
    
    /**
     * Index of variants by affected gene
     * Note that a variant can affect more than one gene, so
     * will appear multiple times in the value side of the map
     */
    Map<String,List<Variant>> genes = null
    
    @CompileStatic
    Map<String,List<Variant>> getGenes() {
        if(genes == null) {
          genes = [:]
          for(Variant v in variants) {
              for(SnpEffInfo eff in v.snpEffInfo.grep { SnpEffInfo inf -> inf.gene }) {
                List geneVars = genes[eff.gene]
                if(geneVars == null) {
                    genes[eff.gene] = (List<Variant>)[]
                }
                genes[eff.gene] << v
              }
          }
        }
        return genes
    }
    
    /**
     * Print out a version of this VCF with only the given samples included
     */
    void filterSamples(PrintStream p, List<String> includeSamples) {
        
        p.println(headerLines[0..-2].join('\n'))
        
        // Remove the unwanted samples from the last header line
        List<String> lastHeaderFields = lastHeaderLine[0..8] + includeSamples
        p.println lastHeaderFields.join("\t")
        def indices = includeSamples.collect { s -> samples.indexOf(s) }*.plus(9)
        System.err.println "Indices of selected samples are $indices"
        for(Variant v in this) {
            // Only write the variant if it has non-zero dosage for at least one sample
            if(includeSamples.any { v.sampleDosage(it)}) {
                String [] variantFields = v.line.split("\t")
                p.println((variantFields[0..8] + variantFields[indices]).join("\t"))
            }
        }
    }
    
    void printHeader(PrintStream p) {
        p.println(headerLines.join('\n'))
    }
    
    void printHeader() {
        printHeader(System.out)
    }
    
   void print() {
        this.print(System.out)
    }
    
    void print(PrintStream p) {
        printHeader(p)
        for(Variant v in variants) {
            p.println(v.line)
        }
    }

    @Override
    public Iterator<Variant> iterator() {
        return this.variants.iterator()
    }
    
    int getSize() {
        this.variants.size()
    }
}