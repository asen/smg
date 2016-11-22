import com.smule.smg.{SMGTree, SMGTreeNode}
import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner

/**
  * Created by asen on 11/22/16.
  */

@RunWith(classOf[JUnitRunner])
class SMGTreeSpec extends Specification {

  case class TNode(id: String, parentId: Option[String]) extends SMGTreeNode

  "SMGTree" should {

    "work" in {

      val parents = Map(
        "pf1" -> TNode("pf1", None),
        "pf2" -> TNode("pf2", None),
        "pf3" -> TNode("pf3", None),
        "pf1-1" -> TNode("pf1-1", Some("pf1")),
        "pf2-1" -> TNode("pf2-1", Some("pf2")),
        "pf3-1" -> TNode("pf3-1", Some("pf2"))
      )

      val leafs = Seq(
        TNode("lf1-1", Some("pf1-1")),
        TNode("lf1-2", Some("pf1-1")),
        TNode("lf2-1", Some("pf2-1")),
        TNode("lf3-1", Some("pf3-1")),
        TNode("lf4-1", Some("pf1"))
      )

      val tt = SMGTree.buildTrees[TNode](leafs, parents)
//      tt.foreach { t => println(t) }

      tt must have size 2
      val t = tt.head
      t.children must have size 2
      t.children.head.children must have size 0
      t.children(1).children must have size 2
    }
  }
}
