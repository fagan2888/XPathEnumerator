package edu.ncrn.cornell.xml

import scala.annotation.tailrec
import scala.xml.{Node, Utility}
import ScalaXmlExtra._
import XpathEnumerator._
import XpathXsdEnumerator._

import scala.util.{Failure, Success, Try}


/**
  * @author Brandon Barker
  *         9/14/2016
  */


//TODO: Attempt to support certain "bounded" uses of xs:any, probably just
//TODO: ##local and ##targetNamespace namespaces : http://www.w3schools.com/xml/el_any.asp
//TODO: Need to flesh out possibilities for anyAttribute anyURI, etc.

// Note: do not use concurrently!
// TODO: make safe by passing around all state as a state object

trait XpathXsdEnumerator extends XpathEnumerator {

  //
  // Maps from an XPath to a reusable (named) element or type;
  // used for scope determination and node lookup
  //
  var namedTypes: XpathNodeMap = Map()
  var namedElements: XpathNodeMap = Map()
  var namedAttributes: XpathNodeMap = Map()

  var nonEmpty: Boolean = true

  object XsdNamedType {
    // Note that an unnamed ComplexType can only be used by the parent element,
    // so we don't need to recognize such unnamed cases here.
    def unapply(arg: Node): Option[String] =
    if (xsdNamedTypes.contains(arg.fullName)) {
      val label = arg.attributeVal("name")
      //TODO probably need a better way to look up namespaces
      label.foreach{labelSome => namedTypes += (labelSome -> arg)}
      label
    }
    else None
  }


  object XsdNamedElement {
    def unapply(arg: Node): Option[String] =
      if (xsdElems.contains(arg.fullName) && isLocallyDefined(arg)) {
        val label = arg.attributeVal("name")
        //TODO probably need a better way to look up namespaces
        label.foreach{labelSome => namedElements += (labelSome -> arg)}
        label
      }
      else None
  }

  object XsdNamedAttribute {
    def unapply(arg: Node): Option[String] =
      if (xsdAttribs.contains(arg.fullName) && isLocallyDefined(arg))
        arg.attributeVal("name")
      else None
  }

  object XsdNamedLocalNode {
    def unapply(arg: Node): Option[String] =
      XsdNamedElement.unapply(arg) orElse XsdNamedAttribute.unapply(arg)
  }


  sealed abstract class NodeWrap(arg: Node)
  case class NodeWrapNoCarry(arg: Node) extends NodeWrap(arg)
  case class NodeWrapCarry(arg: Node, label: String) extends NodeWrap(arg)


  object XsdNonLocalElement {

    def unapplyMatcher(arg: Node, label: String = ""): Option[(String, Try[Node])] = {
      def getLabel(elseWise: String) = if (label != "") label else elseWise
      if (xsdDataNodes.contains(arg.fullName)) arg.attributes.asAttrMap match {
        case attrMap if attrMap.contains("ref") && namedAttributes.contains(attrMap("ref")) =>
          Some(getLabel(attrMap("ref")), Try(namedAttributes(attrMap("ref"))))
        case attrMap if attrMap.contains("ref") =>
          Some(getLabel(attrMap("ref")), Try(namedElements(attrMap("ref"))))
        case attrMap if attrMap.contains("type") =>
          //TODO: probably an oversimplification; may need a list of simple types
          if (attrMap("type").startsWith("xs:")) None
          else Some(getLabel(attrMap("name")), Try(namedTypes(attrMap("type"))))
        case _ => None
      }
      else None
    }

    //TODO: may need to match on (Node, XPath: String)


    def unapply(arg: Node): Option[(String, Try[Node])] = unapply(NodeWrapNoCarry(arg))
    //
    //TODO: also looking up arg.fullName is not sufficient... need whole xpath?
    @tailrec
    def unapply(arg0: NodeWrap): Option[(String, Try[Node])] = {
      arg0 match {
        case NodeWrapNoCarry(arg: Node) =>
          val last = unapplyMatcher(arg)
          last match {
            case Some((labelCarry: String, Success(node: Node))) if !isLocallyDefined(node) =>
              // Keep chasing the reference
              //TODO: above, need to check to see if we need worry about non-head elements
              unapply(NodeWrapCarry(node.child.head, labelCarry))
            case _ => last
          }
        case NodeWrapCarry(arg: Node, label: String) =>
          val last = unapplyMatcher(arg, label)
          last match {
            case Some((labelCarry: String, Success(node: Node))) if !isLocallyDefined(node) =>
              // Keep chasing the reference
              //TODO: above, need to check to see if we need worry about non-head elements
              unapply(NodeWrapCarry(node.child.head, labelCarry))
            case _ => last
          }
      }
    }
  }

  def xsdXpathLabel(node: Node): String = node match {
    case XsdNamedElement(label)   =>  label
    case XsdNamedAttribute(label) => "@" + label
    case XsdNonLocalElement(nodeMaybe) =>
      if (xsdAttribs.contains(node.fullName)) "@" + nodeMaybe._1
      else nodeMaybe._1
    case _ => ""
  }



  def pathifyXsdNodes(nodes: Seq[Node], parPath: String = "/")
  : Seq[(Node, String)] = {
    nodes.groupBy(nn => parPath + xsdXpathLabel(nn)).toList.flatMap{
      case(xpath, labNodes) =>
        labNodes.zipWithIndex.map{case (nn, ii) => (nn, xpath)}
    }
  }

  //implicit val xpathXsdEnumerator: XpathXsdEnumerator = this

  @tailrec
  final def enumerateXsd(
    nodes: Seq[(Node, String, List[Node])], pathData: List[(String, String)] = Nil
   ): List[(String, String)] = nodes.filter(x => nodeFilter(x._1)) match {
    case (node, currentPath, refNodesVisited) +: rest =>
      node match {
        case XsdNamedType(label) =>
          enumerateXsd(rest, pathData)
        case XsdNamedElement(label) =>
          val newElementData =
            if(node.child.isEmpty)
              List((cleanXpath(currentPath), node.attributes.asAttrMap.getOrElse("type", "DEBUG")))
            else Nil
          val newNodes = pathifyXsdNodes(node.child, currentPath + "/")
          enumerateXsd( // Default
            rest ++ newNodes.map(nn => (nn._1, nn._2, refNodesVisited)),
            newElementData ::: pathData
          )
        case XsdNamedAttribute(label) =>
          //TODO probably need a better way to look up namespaces
          namedAttributes += (label -> node)
          val newElementData =
            if(node.child.isEmpty)
              List((cleanXpath(currentPath), node.attributes.asAttrMap.getOrElse("type", "DEBUG")))
            else Nil
          enumerateXsd(rest, newElementData ::: pathData)
        case XsdNonLocalElement(label, nodeMaybe) => nodeMaybe match {
          case Success(refnode) =>
            if (refNodesVisited.contains(refnode)) {
              // Skip adding refnode's children; recursive path
              enumerateXsd(rest, (cleanXpath(currentPath), "recursive!") :: pathData)
            }
            else {
              if (xsdNamedTypes.contains(refnode.fullName) &&
                refnode.attributes.asAttrMap.contains("name")) {
                // This will be a named element that forwards to some other node
                namedElements += (label -> refnode)
              }
              val newElementData =
                List((cleanXpath(currentPath),
                  refnode match {
                    case _ if refnode.attributes.asAttrMap.contains("type") =>
                      refnode.attributes.asAttrMap("type")
                    case _ => refnode.child.headOption match {
                      case Some(child) if child.fullName == "xs:restriction" =>
                        child.attributes.asAttrMap.getOrElse("base", "asdf")
                      case _ => ""
                    }
                  }
                  )).filter(ne => !nonEmpty || ne._2.nonEmpty)
              val newNodes = pathifyXsdNodes(refnode.child, currentPath + "/")
              enumerateXsd(// Continue with refnode's children instead
                rest ++ newNodes.map(nn => (nn._1, nn._2, refnode :: refNodesVisited)),
                newElementData ::: pathData
              )

            }
          case Failure(e) => //TODO: narrow this down to appropriate error
            enumerateXsd( // Not ready yet, let's try again later:
              rest ++ Seq((node, currentPath, refNodesVisited)),
              pathData
            )
        }
        case _ =>
          val newNodes = pathifyXsdNodes(node.child, currentPath)
            enumerateXsd( // Default; no path change
            rest ++ newNodes.map(nn => (nn._1, nn._2, refNodesVisited)),
            pathData
          )
      }
    case Seq() => pathData
  }

  def enumerate(
    nodes: Seq[Node], nonEmpty: Boolean = true,
    newNodeFilter: Node => Boolean = _ => true
  ): List[(String, String)] = {
    namedTypes = Map()
    namedElements = Map()
    namedAttributes = Map()
    this.nonEmpty = nonEmpty
    val initNodes = pathifyXsdNodes(nodes.map(x => Utility.trim(x)), "/")
    enumerateXsd(initNodes.map(nn => (nn._1, nn._2, Nil)))
  }

}


object XpathXsdEnumerator {

  type XpathNodeMap = Map[String, Node]

  // Let's model the types of nodes we care about with extractors,
  // returning None if it isn't an appropriate type

  val xsdElems = List("xs:element")
  val xsdAttribs = List("xs:attribute")
  val xsdNamedTypes = List("xs:simpleType", "xs:complexType")
  val xsdDataNodes = xsdElems ::: xsdAttribs


  def isLocallyDefined(arg: Node) =
    arg.attributeVal("ref").isEmpty && (
      arg.attributeVal("type").isEmpty ||
      arg.attributeVal("type").get.startsWith("xs:")
    )

}

