package robjam1990.analytics.deeplearning;

import java.io.Serializable;

/**
 * The activation function calculates a node's value based on the input value.
 * An inverse calculation is also required for back propagation.
 * The inverse function should equal the derivative of the calculation function.
 * See, https://en.wikipedia.org/wiki/Activation_function
 */
public interface ActivationFunction extends Serializable {
	double calculate(double value);
	double calculateInverse(double value);
}
