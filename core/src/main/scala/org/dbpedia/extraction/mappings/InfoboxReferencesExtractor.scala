package org.dbpedia.extraction.mappings

import java.util

import com.sun.xml.internal.fastinfoset.util.StringArray
import org.dbpedia.extraction.config.provenance.DBpediaDatasets
import org.dbpedia.extraction.transform.Quad

import collection.mutable.{ArrayBuffer, HashSet, ListBuffer}
import org.dbpedia.extraction.ontology.datatypes.{Datatype, DimensionDatatype}
import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.dataparser._
import org.dbpedia.extraction.util.RichString.wrapString
import org.dbpedia.extraction.ontology.Ontology
import org.dbpedia.extraction.util._
import org.dbpedia.extraction.config.mappings.InfoboxExtractorConfig
import org.dbpedia.extraction.wikiparser.WikiPage
import org.dbpedia.extraction.config.dataparser.DataParserConfig
import org.dbpedia.extraction.mappings.MappingsLoader.loadOntologyProperty
import org.dbpedia.extraction.wikiparser.impl.simple.SimpleWikiParser
import org.dbpedia.iri.UriUtils

import scala.collection.mutable
import scala.language.reflectiveCalls

class InfoboxReferencesExtractor(
  context : {
      def mappings : Mappings
    def ontology : Ontology
    def language : Language
    def redirects : Redirects 
  } 
) 
extends PageNodeExtractor
{
    private val minPropertyCount = InfoboxExtractorConfig.minPropertyCount

    private val ontology = context.ontology

    private val language = context.language

    private val redirects = context.redirects

    private val wikiCode = language.wikiCode

    private val minRatioOfExplicitPropertyKeys = InfoboxExtractorConfig.minRatioOfExplicitPropertyKeys

    private val ignoreTemplates = InfoboxExtractorConfig.ignoreTemplates

    private val ignoreTemplatesRegex = InfoboxExtractorConfig.ignoreTemplatesRegex

    private val ignoreProperties = InfoboxExtractorConfig.ignoreProperties

    private val rdfLangStrDt = ontology.datatypes("rdf:langString")

    private val SplitWordsRegex = InfoboxExtractorConfig.SplitWordsRegex

    private val TrailingNumberRegex = InfoboxExtractorConfig.TrailingNumberRegex

    private val templateMappings = context.mappings.templateMappings

    private val resolvedMappings: Map[String, Extractor[TemplateNode]] = context.redirects.resolveMap(templateMappings)

    override val datasets = Set(DBpediaDatasets.InfoboxReferences,DBpediaDatasets.MappedInfoboxReferences)

    override def extract(node : PageNode, subjectUri : String) : Seq[Quad] =
    {
        var refCount = 0
        val citExt = new CitationExtractor(context)

        if(node.title.namespace != Namespace.Main && !ExtractorUtils.titleContainsCommonsMetadata(node.title)) return Seq.empty

        val quads = new ArrayBuffer[Quad]()
        val node2 = new PageNode(node.title, node.id, node.revision, node.timestamp, node.contributorID, node.contributorName, node.source.replace("<ref","Temp_value_before_ref<ref").replaceAll("""\{\{[Rr]\|([^\}]+)\}\}""", "Temp_value_before_ref$1"), node.children)

        val regCom = "<\\!--.*?-->".r
        val current2 = regCom.replaceAllIn(node.source.toString,"")
        val reg = "<ref([^>]*?)>([^<]+)</ref>".r("refn","refv")
        val najti = reg.findAllMatchIn(current2).toArray
        val regz = "<ref([^>/]+)/[ ]?>".r("refz")
        val regz2 = """\{\{[Rr]\|([^\}]+)\}\}""".r("refc")
        var refNames = scala.collection.mutable.Map[String, String]()
        for (ref <- najti) {
            val refB = ref.group("refn")
            val refValue = ref.group("refv")
            if (refB contains "=") {
                var refName = ""
                if (refB.replaceAll(" ","") contains "name=") {
                    val regX = """name[ ]?=[ '\\"]?([^>'\\"]+)[ '\\"]*?>""".r("ref1")
                    val refNameCand = regX.findFirstMatchIn(refB+">")
                    if (refNameCand.isDefined) {
                        refName=refNameCand.get.group("ref1")
                    }
                }
                if (refName!="") {refNames(refName) = refValue}
            }
        }

        var counter = 0

        var templates1lvl = scala.collection.mutable.Map[String, String]()
        var tempContent = new String
        for (c <- current2.toArray) {
            if (c.equals('{')) {
                counter += 1
            }
            if (c.equals('}')) {
                counter -= 1
            }
            if (counter>0) {
                tempContent+=c
            }
            if (counter==0) {
                if (tempContent.length>1) {
                    val checkName = tempContent.split("\\|")
                    //Checking if there is at least one "|" char => adding template to Map
                    if (checkName.length>0){
                        //Checking if there any reference based on "<ref" tag, TODO - add other options: {{R+|.... {{Harv...
                        if ((tempContent contains "<ref") || (tempContent contains "{{r|")){
                            val templateName = checkName(0).trim.replace("{{","")
                            val toAdd = tempContent.slice(1,tempContent.length-1).split("\\|")
                            templates1lvl(templateName) = toAdd.slice(1,toAdd.length).mkString("|")
                        }

                    }
                    tempContent=""
                }
            }
        }
        var infoboxesRaw = scala.collection.mutable.Map[String, scala.collection.mutable.Map[String,String]]()
        for ((tempName,tempContent) <- templates1lvl) {

            var counter = 0 // for brackets { }
            var counter2 = 0 // for brackets [ ]
            var tempValue = ""
            var paramName = ""
            var ParametersRaw = scala.collection.mutable.Map[String, String]()
            val tempContentLen = tempContent.length
            var cntChar = 0
            for (c <- tempContent.toArray) {
                cntChar+=1
                tempValue += c
                if (c.equals('{')) {
                    counter += 1
                }
                if (c.equals('}')) {
                    counter -= 1
                }
                if (c.equals(']')) {
                    counter2 += 1
                }
                if (c.equals('[')) {
                    counter2 -= 1
                }

                if ((c.equals('|') && counter==0 && counter2==0) || cntChar==tempContentLen) {
                    if (tempValue contains "=") {
                        var temp1 = tempValue.split("=")
                        var paramName = temp1(0).trim
                        var paramValue = temp1.slice(1,temp1.length).mkString("=").trim
                        // TODO: to add apart "<ref", {{r| other cases
                        if ((paramValue contains "<ref") || (paramValue contains "{{r|") || (paramValue contains "{{R|")) {
                            if ((paramValue.length>0) && !(paramName contains "|") && !(paramName contains "{") && !(paramName contains "}")) {
                                ParametersRaw(paramName) = paramValue

                            }
                        }
                        tempValue=""
                    }

                }

            }
            infoboxesRaw(tempName) = ParametersRaw
        }

        for { template <- InfoboxReferencesExtractor.collectTemplates(node2)
          getTitle = context.redirects.resolve(template.title).decoded.toLowerCase
          if !ignoreTemplates.contains(getTitle)
          if !ignoreTemplatesRegex.exists(regex => regex.unapplySeq(getTitle).isDefined) 
        }
        {
            val grapH = extractMappings(template, subjectUri)

            var propertiesFound = false
            
            val propertyList = template.children.filterNot(property => ignoreProperties.get(wikiCode).getOrElse(ignoreProperties("en")).contains(property.key.toLowerCase))


            val countPropertyKeys = propertyList.count(property => !property.key.forall(_.isDigit))
            if ((countPropertyKeys >= minPropertyCount) && (countPropertyKeys.toDouble / propertyList.size) > minRatioOfExplicitPropertyKeys)
            {
                for(property <- propertyList) {
                    val propertyUri = getPropertyUri(property.key)

                    try {
                        if (infoboxesRaw contains template.title.decoded) {
                            if (infoboxesRaw(template.title.decoded) contains property.key) {

                                var tT = "template="+template.title.decoded.replace(" ","_")+"&property="+property.key.replace(" ","_")
                                var isMapped = new ListBuffer[String]()
                                                                for(qd<-grapH) {
                                    if (qd.context.contains("template="+template.title.decoded.replace(" ","_")+"&property="+property.key.replace(" ","_"))) {
                                        quads += new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qd.subject, qd.predicate, qd.value, qd.context, rdfLangStrDt)
                                        isMapped+= qd.predicate

                                    }
                                }

                                val rawParameter = infoboxesRaw(template.title.decoded)(property.key)

                                val refWithContent = reg.findAllMatchIn(rawParameter).toArray
                                for (ref <- refWithContent) {
                                    //val refB = ref.group("refn")
                                    refCount+=1
                                    val refValue = ref.group("refv")
                                    quads += new Quad(language, DBpediaDatasets.InfoboxReferences, subjectUri+"#ref:"+refCount, propertyUri, refValue, property.sourceIri, rdfLangStrDt)
                                    if (isMapped.length>0) {
                                        for (isMa<- isMapped){
                                            quads += new Quad(language, DBpediaDatasets.MappedInfoboxReferences, subjectUri+"#ref:"+refCount, isMa, refValue, property.sourceIri, rdfLangStrDt)
                                        }

                                    }
                                    //CitationExtractor need to be fixed: template names without whitespace are ignored (eq. "{{citeweb|...}}
                                    val templateToExt = new WikiPage(node.title,refValue.replace("|", " | "))
                                    val citExtRes = citExt.extract(templateToExt,"")
                                    for (qwa <- citExtRes) {
                                        var qWa2 = new Quad(language, DBpediaDatasets.InfoboxReferences, qwa.subject, qwa.predicate, qwa.value, qwa.context, rdfLangStrDt)

                                        if (qWa2.predicate.contains("isCitedBy")){
                                            qWa2 = new Quad(language, DBpediaDatasets.InfoboxReferences, qwa.subject, qwa.predicate, subjectUri+"#ref:"+refCount, qwa.context, rdfLangStrDt)
                                        }
                                        quads += qWa2
                                        if (isMapped.length>0) {
                                            var qWa3 = new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qwa.subject, qwa.predicate, qwa.value, qwa.context, rdfLangStrDt)
                                            if (qWa3.predicate.contains("isCitedBy")){
                                                qWa3 = new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qwa.subject, qwa.predicate, subjectUri+"#ref:"+refCount, qwa.context, rdfLangStrDt)
                                            }
                                            quads += qWa3
                                        }
                                    }
                                }


                                val refWithOnlyName = regz.findAllMatchIn(rawParameter).toArray
                                if (refWithOnlyName.length > 0) {

                                    for (refi <- refWithOnlyName) {
                                        val nameRe = """name[ '\\"]?=[ ]?[ '\\"]?([^>'\\"]+)""".r("nm").findFirstMatchIn(refi.group("refz")).get.group("nm")
                                        if (refNames.contains(nameRe)) {
                                            refCount+=1
                                            quads += new Quad(language, DBpediaDatasets.InfoboxReferences, subjectUri+"#ref:"+refCount, propertyUri, refNames(nameRe), property.sourceIri, rdfLangStrDt)
                                            if (isMapped.length>0) {
                                                for (isMa<- isMapped){
                                                    quads += new Quad(language, DBpediaDatasets.MappedInfoboxReferences, subjectUri+"#ref:"+refCount, isMa, refNames(nameRe), property.sourceIri, rdfLangStrDt)
                                                }
                                            }
                                            val templateToExt = new WikiPage(node.title,refNames(nameRe).replace("|", " | "))
                                            val citExtRes = citExt.extract(templateToExt,"")
                                            for (qwa <- citExtRes) {
                                                var qWa2 = new Quad(language, DBpediaDatasets.InfoboxReferences, qwa.subject, qwa.predicate, qwa.value, qwa.context, rdfLangStrDt)

                                                if (qWa2.predicate.contains("isCitedBy")){
                                                    qWa2 = new Quad(language, DBpediaDatasets.InfoboxReferences, qwa.subject, qwa.predicate, subjectUri+"#ref:"+refCount, qwa.context, rdfLangStrDt)
                                                }
                                                quads += qWa2
                                            if (isMapped.length>0) {
                                                var qWa3 = new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qwa.subject, qwa.predicate, qwa.value, qwa.context, rdfLangStrDt)
                                                if (qWa3.predicate.contains("isCitedBy")){
                                                    qWa3 = new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qwa.subject, qwa.predicate, subjectUri+"#ref:"+refCount, qwa.context, rdfLangStrDt)
                                                }
                                                quads += qWa3
                                            }
                                            }
                                        }

                                    }
                                }


                                val refWithOnlyName2 = regz2.findFirstMatchIn(rawParameter).toArray
                                if (refWithOnlyName2.length > 0) {

                                    for (refi <- refWithOnlyName2) {
                                        val refK = refi.group("refc").split("\\|")
                                        for (refiz <- refK) { // why if name contains \?
                                            if (refNames.contains(refiz)) {
                                                refCount+=1
                                                quads += new Quad(language, DBpediaDatasets.InfoboxReferences, subjectUri+"#ref:"+refCount, propertyUri, refNames(refiz.replace("\\", "")), property.sourceIri, rdfLangStrDt)
                                                if (isMapped.length>0) {
                                                    for (isMa<- isMapped){
                                                        quads += new Quad(language, DBpediaDatasets.MappedInfoboxReferences, subjectUri+"#ref:"+refCount, isMa, refNames(refiz.replace("\\", "")), property.sourceIri, rdfLangStrDt)
                                                    }
                                                }
                                                val templateToExt = new WikiPage(node.title,refNames(refiz.replace("\\", "")).replace("|", " | "))
                                                val citExtRes = citExt.extract(templateToExt,"")
                                                for (qwa <- citExtRes) {
                                                    var qWa2 = new Quad(language, DBpediaDatasets.InfoboxReferences, qwa.subject, qwa.predicate, qwa.value, qwa.context, rdfLangStrDt)
                                                    if (qWa2.predicate.contains("isCitedBy")){
                                                        qWa2 = new Quad(language, DBpediaDatasets.InfoboxReferences, qwa.subject, qwa.predicate, subjectUri+"#ref:"+refCount, qwa.context, rdfLangStrDt)
                                                    }
                                                    quads += qWa2
                                                    if (isMapped.length>0) {
                                                        var qWa3 = new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qwa.subject, qwa.predicate, qwa.value, qwa.context, rdfLangStrDt)
                                                        if (qWa3.predicate.contains("isCitedBy")){
                                                            qWa3 = new Quad(language, DBpediaDatasets.MappedInfoboxReferences, qwa.subject, qwa.predicate, subjectUri+"#ref:"+refCount, qwa.context, rdfLangStrDt)
                                                        }
                                                        quads += qWa3
                                                    }

                                                }

                                            }

                                        }

                                    }
                                }


                            }
                        }
                    }

                        catch
                        {
                            case ex: IllegalArgumentException => println(ex)
                        }
                        propertiesFound = true





                }
            }
        }
        quads

    }


    private def getPropertyUri(key : String) : String =
    {

        var result = key.toLowerCase(language.locale).trim
        result = result.toCamelCase(SplitWordsRegex, language.locale)

        result = TrailingNumberRegex.replaceFirstIn(result, "")

        result = WikiUtil.cleanSpace(result)

        language.propertyUri.append(result)
    }

    private def extractMappings(node : Node, subjectUri : String) : Seq[Quad] =
    {
        val graph = node match
        {
            case templateNode : TemplateNode =>
            {
                resolvedMappings.get(templateNode.title.decoded) match
                {
                    case Some(mapping) => {
                        mapping.extract(templateNode, subjectUri)
                    }
                    case None => Seq.empty
                }
            }
            case _ => Seq.empty
        }

        if(graph.isEmpty)
        {
            node.children.flatMap(child => extractMappings(child, subjectUri))
        }
        else
        {
            graph
        }
    }

}

object InfoboxReferencesExtractor {

    def collectTemplates(node : Node) : List[TemplateNode] =
    {
        node match
        {
            case templateNode : TemplateNode => List(templateNode)
            case _ => node.children.flatMap(collectTemplates)
        }
    }

    def collectProperties(node : Node) : List[PropertyNode] =
    {
        node match
        {
            case propertyNode : PropertyNode => List(propertyNode)
            case _ => node.children.flatMap(collectProperties)
        }
    }
}
