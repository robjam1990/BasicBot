package robjam1990.analytics.deeplearning;

/**
 * Uses the Tanh function.
 * Tanh is a non-linear (S curve) function bounded by -1 and +1.
 * See, https://en.wikipedia.org/wiki/Hyperbolic_function#Hyperbolic_tangent
 */
public class TanhActivationFunction implements ActivationFunction {
	@Override
	public double calculate(double value) {
		return tanh(value);
	}

	@Override
	public double calculateInverse(double value) {
		double result = tanh(value);
		return 1.0 - (result * result);
	}
	
	private double tanh(double value) {
		return Math.tanh(value);
	}
}
