package pt.up.fe.comp2023.optimization;

import org.specs.comp.ollir.*;
import pt.up.fe.comp2023.optimization.interferenceGraph.MyInterferenceGraph;

import java.util.*;

import static org.specs.comp.ollir.InstructionType.*;
import static pt.up.fe.comp2023.optimization.OptimizationUtils.*;

public class RegisterAllocation {
    private final Method method;
    private final int registerAllocationOption;
    private Map<String, Integer> optimalRegisters = new HashMap<>();
    private final Map<Node, Set<String>> defs = new HashMap<>();
    private final Map<Node, Set<String>> uses = new HashMap<>();
    private final Map<Node, Set<String>> in = new HashMap<>();
    private final Map<Node, Set<String>> out = new HashMap<>();
    private final MyInterferenceGraph interferenceGraph = new MyInterferenceGraph();

    public RegisterAllocation(Method method, int registerAllocationOption) {
        this.registerAllocationOption = registerAllocationOption;
        this.method = method;

        livenessAnalysis();
        createInterferenceGraph();
        graphColoring();    //Updates optimalRegisters
        updateVirtualRegisters();
    }

    private void livenessAnalysis(){
        this.method.buildCFG();
        for (Instruction instruction : this.method.getInstructions()){
            this.defs.put(instruction, getDef(instruction));
            this.uses.put(instruction, getUse(instruction, new HashSet<>()));
        }
        computeLiveInOut();
    }

    private void createInterferenceGraph() {
        List<String> localVars = getLocalVars(this.method);

        //Add a node for each variable
        for(String var : localVars)
            this.interferenceGraph.addNode(var);

        //Compute edges
        for(Instruction instruction : this.method.getInstructions()){
            List<String> liveIn = new ArrayList<>(this.in.get(instruction));
            List<String> defAndLiveOut = new ArrayList<>(unionSets(this.defs.get(instruction), this.out.get(instruction)));

            this.interferenceGraph.connectInterferingVariables(liveIn);
            this.interferenceGraph.connectInterferingVariables(defAndLiveOut);
        }
    }

    private void graphColoring() {
        if(registerAllocationOption > 0) { // Try to use at most <n> local variables
            int k = registerAllocationOption - methodAccessThis(method) - numParams(method);
            this.optimalRegisters = this.interferenceGraph.isMColoringFeasible(k);
        }
        else // Try to use as few local variables as it can
            this.optimalRegisters = this.interferenceGraph.findOptimalColoring();
    }

    private void updateVirtualRegisters(){
        int firstLocalVarRegister = methodAccessThis(method) + numParams(method);

        for (Map.Entry<String, Integer> entry : optimalRegisters.entrySet()) {
            String var = entry.getKey();
            Integer register = entry.getValue();
            int virtualRegister = firstLocalVarRegister + register;
            
            method.getVarTable().get(var).setVirtualReg(virtualRegister);
        }
    }

    private Set<String> getDef(Instruction instruction){
        Set<String> def = new HashSet<>();

        if(instruction.getInstType() == ASSIGN) {
            AssignInstruction assignInst = (AssignInstruction)instruction;
            if(isLocalVar(assignInst.getDest(), this.method)){
                Element dest = assignInst.getDest();
                def.add(toVarName(dest));
            }
        }
        return def;
    }

    private Set<String> getUse(Instruction instruction, Set<String> result){
        switch (instruction.getInstType()) {
            case ASSIGN -> {
                AssignInstruction assignInst = (AssignInstruction) instruction;
                return getUse(assignInst.getRhs(), result);
            }
            case CALL -> {
                CallInstruction callInst = (CallInstruction) instruction;
                List<Element> arguments = callInst.getListOfOperands();
                for (Element argument : arguments) {
                    if (!argument.isLiteral() && isLocalVar(argument, this.method))
                        result.add(toVarName(argument));
                }
            }
            case RETURN -> {
                ReturnInstruction returnInst = (ReturnInstruction) instruction;
                Element returnElement = returnInst.getOperand();
                if (returnElement != null && !returnElement.isLiteral() && isLocalVar(returnElement, this.method))
                    result.add(toVarName(returnElement));
            }
            case UNARYOPER -> {
                UnaryOpInstruction unaryOpInstruction = (UnaryOpInstruction) instruction;
                Element operand = unaryOpInstruction.getOperand();
                if (!operand.isLiteral() && isLocalVar(operand, this.method))
                    result.add(toVarName(operand));
            }
            case BINARYOPER -> {
                BinaryOpInstruction binInst = (BinaryOpInstruction) instruction;
                Element leftOperand = binInst.getLeftOperand();
                Element rightOperand = binInst.getRightOperand();
                if (!leftOperand.isLiteral() && isLocalVar(leftOperand, this.method))
                    result.add(toVarName(leftOperand));
                if (!rightOperand.isLiteral() && isLocalVar(rightOperand, this.method))
                    result.add(toVarName(rightOperand));
            }
            case NOPER -> {
                SingleOpInstruction singleOpInstruction = (SingleOpInstruction) instruction;
                Element rightOperand = singleOpInstruction.getSingleOperand();
                if (!rightOperand.isLiteral() && isLocalVar(rightOperand, this.method))
                    result.add(toVarName(rightOperand));
            }
            case PUTFIELD -> {
                PutFieldInstruction putFieldInstruction = (PutFieldInstruction) instruction;
                Element rightOperand = putFieldInstruction.getThirdOperand();
                if (!rightOperand.isLiteral() && isLocalVar(rightOperand, this.method))
                    result.add(toVarName(rightOperand));
            }
        }
        return result;
    }

    private void computeLiveInOut() {
        for (Instruction instruction : method.getInstructions()){
            this.in.put(instruction, new HashSet<>());
            this.out.put(instruction, new HashSet<>());
        }

        boolean liveChanged;
        do {
            liveChanged = false;
            for(Instruction instruction : method.getInstructions()){
                //Save current liveIn and liveOut
                Set<String> liveInAux = new HashSet<>(this.in.get(instruction));
                Set<String> liveOutAux = new HashSet<>(this.out.get(instruction));

                //Update liveIn
                Set<String> difference = differenceSets(this.out.get(instruction), this.defs.get(instruction));
                Set<String> newLiveIn = unionSets(this.uses.get(instruction), difference);
                this.in.put(instruction, newLiveIn);

                //Update liveOut
                Set<String> newLiveOut = new HashSet<>();

                for(Node successor : instruction.getSuccessors()){
                    Set<String> liveInSuccessor =  this.in.get(successor);
                    newLiveOut = unionSets(newLiveOut, liveInSuccessor);
                }
                this.out.put(instruction, newLiveOut);

                //Check if liveIn or liveOut changed
                if(!liveInAux.equals(newLiveIn) || !liveOutAux.equals(newLiveOut))
                    liveChanged = true;
            }
        } while(liveChanged);
    }
}
