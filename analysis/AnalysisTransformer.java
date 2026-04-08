import java.util.*;
import soot.*;
import soot.jimple.Stmt;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.jimple.toolkits.callgraph.Edge;
public class AnalysisTransformer extends SceneTransformer{
    static CallGraph cg;
    protected void internalTransform(String phaseName, Map<String,String> options){
        // store the call graph as static field
        cg = Scene.v().getCallGraph();
        var entryPoints = Scene.v().getEntryPoints();
        assert(entryPoints.size()==1);
        // This code lets us get the main method, our test cases will have only one entry point that is the main method
        // in Test class
        SootMethod entryMethod = entryPoints.get(0);
        handleMainMethod(entryMethod);
    }
    void handleMainMethod(SootMethod method){
        Body body = method.getActiveBody();
        for(Unit u : body.getUnits()){
            Stmt stmt = (Stmt)u;
            int lineNumber = stmt.getJavaSourceStartLineNumber();
            if(stmt.containsInvokeExpr()){
                System.out.println("Call site found : " + stmt + "@" + lineNumber);
                Iterator<Edge> targets = cg.edgesOutOf(stmt);
                while(targets.hasNext()){
                    Edge edge = targets.next();
                    SootMethod targetMethod = edge.tgt();
                    System.out.println(" - > Potential target" + targetMethod.getSignature());
                }
            }
        }
        }
}