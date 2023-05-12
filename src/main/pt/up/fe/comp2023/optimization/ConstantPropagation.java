package pt.up.fe.comp2023.optimization;

import pt.up.fe.comp.jmm.analysis.JmmSemanticsResult;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.Type;
import pt.up.fe.comp.jmm.ast.AJmmVisitor;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.JmmNodeImpl;
import pt.up.fe.comp2023.semantic.MySymbolTable;

import java.util.HashMap;
import java.util.Map;

import static pt.up.fe.comp2023.optimization.OptimizationUtils.intersectMaps;
import static pt.up.fe.comp2023.semantic.SemanticUtils.getIdentifierType;

public class ConstantPropagation extends AJmmVisitor<Map<String, String>, Void> {
    private final JmmSemanticsResult semanticsResult;
    private final SymbolTable symbolTable;  //TODO: remove
    private String currentMethodName;   //TODO: remove
    private boolean codeModified;

    public ConstantPropagation (JmmSemanticsResult semanticsResult){
        this.semanticsResult = semanticsResult;
        this.symbolTable = semanticsResult.getSymbolTable();
    }

    public boolean apply(){
        this.codeModified = false;
        Map<String, String> constants = new HashMap<>();

        visit(semanticsResult.getRootNode(), constants);
        return this.codeModified;
    }

    @Override
    protected void buildVisitor() {
        setDefaultVisit(this::setDefaultVisit);
        addVisit("MethodDecl", this::changeCurrentMethodName);
        addVisit("VoidMethodDecl", this::changeCurrentMethodName);
        addVisit("MainMethodDecl", this::changeCurrentMethodName);
        addVisit("Condition", this::checkIfElseCondition);
        addVisit("Cycle", this::dealWithCycle);
        addVisit("Assignment", this::dealWithAssignment);
        addVisit("Identifier", this::dealWithIdentifier);
    }

    private Void setDefaultVisit(JmmNode jmmNode, Map<String, String> constants) {
        for (JmmNode child: jmmNode.getChildren())
            visit(child, constants);
        return null;
    }

    private Void changeCurrentMethodName(JmmNode jmmNode, Map<String, String> constants) {
        this.currentMethodName = jmmNode.get("methodname"); //TODO: remove
        constants.clear();

        for (JmmNode child: jmmNode.getChildren())
            visit(child, constants); //each statement modifies the map
        return null;
    }

    private Void checkIfElseCondition(JmmNode jmmNode, Map<String, String> constants) {
        JmmNode conditionNode = jmmNode.getJmmChild(0);
        JmmNode ifTrue = jmmNode.getJmmChild(1);
        JmmNode ifFalse = jmmNode.getJmmChild(2);
        int ifElseIndex = jmmNode.getIndexOfSelf();
        visit(conditionNode, constants);

        //TODO: [refactor] create a method to execute this changes
        if (conditionNode.getKind().equals("Boolean")) {
            // if condition value is true, the code inside the 'ifTrue' node will be executed
            // else, the code inside the 'ifFalse' node will be executed.
            JmmNode reachedCode = conditionNode.get("value").equals("true") ? ifTrue : ifFalse;

            if (reachedCode.getKind().equals("CodeBlock")){
                for(JmmNode child : reachedCode.getChildren())
                    jmmNode.getJmmParent().add(child, child.getIndexOfSelf() + ifElseIndex);
            }
            else
                jmmNode.getJmmParent().add(reachedCode, ifElseIndex);
            jmmNode.delete();
            this.codeModified = true;
        }

        else {  //Condition value is undefined
            Map<String, String> ifTrueConstants =  new HashMap<>(constants);
            Map<String, String> ifFalseConstants =  new HashMap<>(constants);

            visit(ifTrue, ifTrueConstants);
            visit(ifFalse, ifFalseConstants);
            constants = intersectMaps(ifTrueConstants, ifFalseConstants);
        }
        return null;
    }

    private Void dealWithCycle(JmmNode jmmNode, Map<String, String> constants) {
        JmmNode conditionNode = jmmNode.getJmmChild(0);
        visit(conditionNode, constants);

        if(conditionNode.getKind().equals("Boolean") && conditionNode.get("value").equals("false")) //Dead code
            jmmNode.getJmmParent().removeJmmChild(jmmNode);
        else {
            //TODO: check which variables are modified and remove them from the map
            for (JmmNode child: jmmNode.getChildren())
                visit(child, constants); //each statement modifies the map
        }
        return null;
    }

    private Void dealWithAssignment(JmmNode jmmNode, Map<String, String> constants) {
        String varName = jmmNode.get("varname");
        JmmNode exprNode = jmmNode.getJmmChild(0);
        visit(exprNode, constants);

        if (exprNode.getKind().equals("Integer") || exprNode.getKind().equals("Boolean"))
            constants.put(varName, exprNode.get("value"));
        else //Unknown Value
            constants.remove(varName);
        return null;
    }

    private Void dealWithIdentifier(JmmNode jmmNode, Map<String, String> constants) {
        String identifierName = jmmNode.get("value");
        String constant = constants.get(identifierName);

        if(constant != null) {
            JmmNode newNode;
            if(constant.equals("true") || constant.equals("false")) //Boolean constant
                newNode = new JmmNodeImpl("Boolean");
            else  //Integer constant
                newNode = new JmmNodeImpl("Integer");
            newNode.put("value", constant);
            jmmNode.replace(newNode);
            this.codeModified = true;
        }
        return null;
    }
}
