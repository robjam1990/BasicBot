package robjam1990.analytics.deeplearning;

/**
 * Uses the Sigmoid function.
 * Sigmoid is a non-linear (S curve) function bounded by 0 and +1.
 * See, https://en.wikipedia.org/wiki/Sigmoid_function
 */
public class SigmoidActivationFunction implements ActivationFunction {
	@Override
	public double calculate(double value) {
		return sigmoid(value);
	}

	@Override
	public double calculateInverse(double value) {
		double result = sigmoid(value);
		return result * (1.0d - result);
	}
	
	private double sigmoid(double value) {
		return 1.0d / (1.0d + Math.exp(-value));
	}
}
