package edu.ncrn.cornell.xml

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.xml.{Elem, Node}
import scala.concurrent.blocking
import ScalaXmlExtra._
import XpathEnumerator._
import XpathXsdEnumerator._

import scala.util.{Failure, Success, Try}


/**
  * @author Brandon Barker
  *         9/14/2016
  */

//TODO: things to deal with: element+name, refs, types, attributes
//
//TODO: need to assume only non-recursive xpaths are of interest, can probably do this by
//TODO: counting references to particular xml types on an xpath

//TODO: Attempt to support certain "bounded" uses of xs:any, probably just
//TODO: ##local and ##targetNamespace namespaces : http://www.w3schools.com/xml/el_any.asp
//TODO: Need to flesh out possibilities for anyAttribute anyURI, etc.

// Note: do not use concurrently!

trait XpathXsdEnumerator extends XpathEnumerator {

  //
  // Maps from an XPath to a reusable (named) element or type;
  // used for scope determination and node lookup
  //
  var namedTypes: XpathNodeMap = Map()
  var namedElements: XpathNodeMap = Map()

  //TODO: may need to match on (Node, XPath: String)
  object XsdNonLocalElement {
    //TODO: also looking up arg.fullName is not sufficient... need whole xpath?
    def unapply(arg: Node): Option[(String, Try[Node])] =
      if (xsdElems.contains(arg.fullName)) arg.attributes.asAttrMap match {
        case attrMap if attrMap.contains("ref") =>
          Some(attrMap("ref"), Try(namedElements(attrMap("ref"))))
        case attrMap if attrMap.contains("type") =>
          //TODO: probably an oversimplification; may need a list of simple types
          if (attrMap("type").startsWith("xs:")) None
          else Some(attrMap("name"), Try(namedTypes(attrMap("type"))))
        case _ => None
      }
      else None
  }

  //  object XsdNamedNode {
  //    def unapply(arg: Node): Option[String] =
  //      XsdNamedLocalNode.unapply(arg) orElse XsdNonLocalElement.unapply(arg)
  //  }

  def xsdXpathLabel(node: Node): String = node match {
    case XsdNamedLocalNode(label) => label
    case XsdNonLocalElement(nodeMaybe) => nodeMaybe._1
    case _ => ""
  }

  def pathifyXsdNodes(nodes: Seq[Node], parPath: String = "/")
  : Seq[(Node, String)] = {
    nodes.groupBy(nn => parPath + xsdXpathLabel(nn)).toList.flatMap{
      case(xpath, labNodes) =>
        //TODO: replace xindex with multiplicity (wildcars) taking into account maxOccurs
        // def xindex(index: Int) = if (labNodes.size > 1) s"[${index + 1}]" else ""
        labNodes.zipWithIndex.map{case (nn, ii) => (nn, xpath)}
    }
  }

  //implicit val xpathXsdEnumerator: XpathXsdEnumerator = this

  @tailrec
  final def enumerateXsd(
     nodes: Seq[(Node, String)], pathData: List[(String, String)] = Nil
   ): List[(String, String)] = nodes match {
    case (node, currentPath) +: rest =>
      println(s"operating on ${node.fullName} with current path $currentPath")
      val newElementData =
        if(node.child.isEmpty) List((cleanXpath(currentPath), node.text))
        else Nil
      val newAttributeData = node.attributes.asAttrMap.map{
        case (key, value) => (currentPath + "/@" + key, value)
      }.toList
      node match {
        case XsdNamedType(label) =>
          println(s"XsdNamedType: $label") //DEBUG
          namedTypes += (cleanXpath(currentPath) -> node)
          enumerateXsd( // Default
            rest ++ pathifyXsdNodes(node.child, currentPath + "/"),
            newElementData ::: newAttributeData ::: pathData
          )
        case XsdNamedElement(label) =>
          println(s"XsdNamedElement: $label") //DEBUG
          namedElements += (cleanXpath(currentPath) -> node)
          enumerateXsd( // Default
            rest ++ pathifyXsdNodes(node.child, currentPath + "/"),
            newElementData ::: newAttributeData ::: pathData
          )
        case XsdNonLocalElement(label, nodeMaybe) => nodeMaybe match {
          case Success(refnode) =>
            println(s"XsdNonLocalElement: $label, Success") //DEBUG
            enumerateXsd( // Continue with refnode's children instead
              rest ++ pathifyXsdNodes(refnode.child, currentPath + "/"),
              newElementData ::: newAttributeData ::: pathData
            )
          case Failure(e) => //TODO: narrow this down to appropriate error
            println(s"XsdNonLocalElement: $label, Failure") //DEBUG
            println(e) // DEBUG
            enumerateXsd( // Not ready yet, let's try again later:
              rest ++ Seq((node, currentPath)),
              newElementData ::: newAttributeData ::: pathData
            )
        }
        case _ =>
          println(s"No labeled match.") //DEBUG
          enumerateXsd( // Default; no path change
            rest ++ pathifyXsdNodes(node.child, currentPath),
            newElementData ::: newAttributeData ::: pathData
          )
      }
    case Seq() => pathData
  }

  def enumerate(
    nodes: Seq[Node], nonEmpty: Boolean = false
  ): List[(String, String)] = enumerateXsd(pathifyXsdNodes(nodes, "/"))

}


object XpathXsdEnumerator {

  type XpathNodeMap = Map[String, Node]

  // Let's model the types of nodes we care about with extractors,
  // returning None if it isn't an appropriate type

  val xsdElems = List("xs:element")
  val xsdAttribs = List("xs:attribute")
  val xsdComplexTypes = List("xs:complexType")
  val xsdNamedNodes = xsdElems ::: xsdAttribs :: xsdComplexTypes


  object XsdNamedType {
    // Note that an unnamed ComplexType can only be used by the parent element,
    // so we don't need to recognize such unnamed cases here.
    def unapply(arg: Node): Option[String] =
    if (xsdComplexTypes.contains(arg.fullName)) arg.attributeVal("name") else None
  }


  object XsdNamedElement {
    def unapply(arg: Node): Option[String] = {
      println(s"XsdNamedElement fullName in unapply: ${arg.fullName}")
      if (arg.attributeVal("type").nonEmpty) {
        println(s"type for ${arg.fullName} is ${arg.attributeVal("type").get}")
      }
      if (xsdElems.contains(arg.fullName) &&
        arg.attributeVal("ref").isEmpty &&
        //TODO: may need to consider simple types more precisely
        (arg.attributeVal("type").isEmpty || arg.attributeVal("type").get.startsWith("xs:"))
      ) arg.attributeVal("name")
      else None
    }
  }

  object XsdNamedAttribute {
    def unapply(arg: Node): Option[String] =
      if (xsdAttribs.contains(arg.fullName)) arg.attributeVal("name") else None
  }

  object XsdNamedLocalNode {
    def unapply(arg: Node): Option[String] =
      XsdNamedElement.unapply(arg) orElse XsdNamedAttribute.unapply(arg)
  }



  //            blocking{
  //            while(!caller.namedElements.contains(arg.fullName)) {}
  //            caller.namedElements(arg.fullName)
  //          }




}

