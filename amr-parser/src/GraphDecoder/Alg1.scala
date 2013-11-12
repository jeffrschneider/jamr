package edu.cmu.lti.nlp.amr.GraphDecoder
import edu.cmu.lti.nlp.amr._

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.lang.Math.abs
import java.lang.Math.log
import java.lang.Math.exp
import java.lang.Math.random
import java.lang.Math.floor
import java.lang.Math.min
import java.lang.Math.max
import scala.io.Source
import scala.util.matching.Regex
import scala.collection.mutable.Map
import scala.collection.mutable.Set
import scala.collection.mutable.ArrayBuffer
import Double.{NegativeInfinity => minusInfty}

class Alg1(featureNames: List[String], labelSet: Array[(String, Int)], connectedConstraint: String = "none")
    extends Decoder(featureNames) {
    // Base class has defined:
    // val features: Features

    def decode(input: Input) : DecoderResult = {
        // Assumes that Node.relations has been setup correctly for the graph fragments
        val graph = input.graph.duplicate
        val nodes : List[Node] = graph.nodes.filter(_.name != None).toList    // TODO: test to see if a view is faster
        val graphObj = new GraphObj(graph, nodes.toArray, features)    // graphObj keeps track of the connectivity of the graph as we add edges
        features.input = input

        logger(1, "Alg1")
        //logger(2, "weights = " + features.weights)

        for { (node1, index1) <- nodes.zipWithIndex
              relations = node1.relations.map(_._1)
              (label, maxCardinality) <- labelSet } {

            if (relations.count(_ ==label) == 0) {   // relations.count(_ == label) counts the edges that are already in the graph fragments
                // Search over the nodes, and pick the ones with highest score
                val nodes2 : List[(Node, Int, Double)] = nodes.zipWithIndex.map(x => (x._1, x._2, features.localScore(node1, x._1, label))).filter(x => x._3 > 0 && x._1.id != node1.id).sortBy(-_._3).take(maxCardinality)

                for ((node2, index2, weight) <- nodes2) {
                    logger(1, "Adding edge ("+node1.concept+", "+label +", "+node2.concept + ") with weight "+weight.toString)
                    graphObj.addEdge(node1, index1, node2, index2, label, weight)
                }
                if (nodes2.size > 0) {
                    logger(2, "node1 = " + node1.concept)
                    logger(2, "label = " + label)
                    logger(2, "nodes2 = " + nodes.toString)
                    //logger(1, "feats = " + feats.toString)
                }
            } else if (relations.count(_ == label) < maxCardinality) {   // relations.count(_ == label) counts the edges that are already in the graph fragments
                // Search over the nodes, and pick the ones with highest score
                val relationIds : List[String] = node1.relations.map(_._2.id)   // we assume if there is a relation already in the fragment, we can't add another relation type between the two nodes
                val nodes2 : List[(Node, Int, Double)] = nodes.zipWithIndex.map(x => (x._1, x._2, features.localScore(node1, x._1, label))).filter(x => x._3 > 0 && x._1.id != node1.id && !relationIds.contains(x._1.id)).sortBy(-_._3).take(maxCardinality - relations.count(_ == label))
                for ((node2, index2, weight) <- nodes2) {
                    logger(1, "Adding edge ("+node1.concept+", "+label +", "+node2.concept + ") with weight "+weight.toString)
                    graphObj.addEdge(node1, index1, node2, index2, label, weight)
                }
                if (nodes2.size > 0) {
                    logger(2, "node1 = " + node1.concept)
                    logger(2, "label = " + label)
                    logger(2, "nodes2 = " + nodes.toString)
                    //logger(1, "feats = " + feats.toString)
                }
            }
        }

        if (connectedConstraint == "and" && !graphObj.connected) {
            // We need to add a top level 'and' node, and make it the root
            // We will pick it's children by rootScore, but won't add any features to the feature vector
            // because it would adversely affect learning
            val children = (
                for { setid: Int <- graphObj.set.toList.distinct
                    } yield {
                      graphObj.setArray(setid).map(nodeIndex => { val node = graphObj.nodes(nodeIndex); (node, features.rootScore(node)) }).maxBy(_._2)._1
                })

            val relations = children.map(x => (":op",x))
            graph.root = Node(graph.nodes.size.toString,    // id       TODO: check that this id doesn't conflict
                              Some("a99"),                  // name     TODO: pick a better name
                              "and",                        // concept
                              relations,                    // relations
                              relations,                    // topologicalOrdering
                              List(),                       // variableRelations
                              None,                         // alignment
                              ArrayBuffer())                // spans
            graph.getNodeById(graph.root.id) = graph.root
            graph.getNodeByName(graph.root.name.get) = graph.root
            graphObj.set = graphObj.set.map(x => 0) // update the graphObj so it knows the graph is connected
            graphObj.setArray(0) ++= Range(0, graphObj.set.size)
        } else {
            if (features.rootFeatureFunctions.size != 0) {
                graph.root = nodes.map(x => (x, features.rootScore(x))).maxBy(_._2)._1
            } else {
                graph.root = nodes(0)
            }
            graphObj.feats += features.rootFeatures(graph.root)
            graphObj.score += features.rootScore(graph.root)
        }

        nodes.map(node => { node.relations = node.relations.reverse })
        if (connectedConstraint != "none") {
            graph.makeTopologicalOrdering()   // won't work if not connected
        }
        return DecoderResult(graph, graphObj.feats, graphObj.score)
    }
}

