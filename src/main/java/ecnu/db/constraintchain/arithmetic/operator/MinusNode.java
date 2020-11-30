package ecnu.db.constraintchain.arithmetic.operator;

import ecnu.db.constraintchain.arithmetic.ArithmeticNode;
import ecnu.db.constraintchain.arithmetic.ArithmeticNodeType;
import ecnu.db.exception.TouchstoneException;
import ecnu.db.exception.schema.CannotFindColumnException;

/**
 * @author wangqingshuai
 */
public class MinusNode extends ArithmeticNode {
    public MinusNode() {
        super(ArithmeticNodeType.MINUS);
    }

    @Override
    public float[] getVector() throws TouchstoneException {
        float[] leftValue = leftNode.getVector(), rightValue = rightNode.getVector();
        for (int i = 0; i < leftValue.length; i++) {
            leftValue[i] -= rightValue[i];
        }
        return leftValue;
    }

    @Override
    public double[] calculate() throws CannotFindColumnException {
        double[] leftValue = leftNode.calculate(), rightValue = rightNode.calculate();
        for (int i = 0; i < leftValue.length; i++) {
            leftValue[i] -= rightValue[i];
        }
        return leftValue;
    }

    @Override
    public String toString() {
        return String.format("minus(%s, %s)", leftNode.toString(), rightNode.toString());
    }
}
