package com.herabarman.meshnet.kit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * The learner that decides, on its own, whether handing a copy of a stored message to a neighbour is a good
 * move. It is an online logistic-regression model with epsilon-greedy exploration, trained on what actually
 * happened to earlier hand-overs (delivered = reward 1, never delivered = 0). It starts from sensible priors so it
 * works on day one, then adapts to this phone's own surroundings: if a neighbour that CLAIMS to reach the
 * destination keeps failing, the weight on its claim shrinks, so it learns to distrust liars and black holes.
 *
 * It is small on purpose (10 numbers, no cloud, no data leaves the phone): this is on-device machine learning,
 * not a large neural network.
 *
 * features: bias, claimed predictability of the neighbour to the destination, its gain over mine, neighbour
 * reliability so far, link quality, message age, priority, copies left, "destination is probably near me now".
 */
final class DtnBrain {
    static final int N = 9;
    private final double[] w = {-1.0, 3.0, 2.0, 1.5, 0.5, 0.3, 0.3, 0.2, -0.5};
    private final double[] vel = new double[N];
    private double wInteract = 0.65; // ReLU interaction: neighbor predictability x reliability
    private double lr = 0.06;
    private double eps = 0.10;
    private final Random rnd;
    long updates;

    DtnBrain(Random rnd) { this.rnd = rnd; }

    double predict(double[] x) {
        double z = 0;
        for (int i = 0; i < N; i++) z += w[i] * x[i];
        double inter = x[1] * x[3];
        if (inter > 0) z += wInteract * inter;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** @return true to hand over a copy */
    boolean decide(double[] x) { return predict(x) >= 0.5 || rnd.nextDouble() < eps; }

    void learn(double[] x, double y) {
        double err = y - predict(x);
        for (int i = 0; i < N; i++) {
            double grad = err * x[i] - 0.001 * w[i];
            vel[i] = 0.75 * vel[i] + lr * grad;
            w[i] = Math.max(-8, Math.min(8, w[i] + vel[i]));
        }
        double inter = x[1] * x[3];
        if (inter > 0) {
            wInteract = Math.max(-4, Math.min(4, wInteract + lr * 0.5 * err * inter));
        }
        eps = Math.max(0.02, eps * 0.992);
        updates++;
    }

    double[] weights() { return w.clone(); }
    double exploration() { return eps; }

    void write(DataOutputStream o) throws IOException {
        for (double v : w) o.writeFloat((float) v);
        o.writeFloat((float) eps);
        o.writeLong(updates);
    }

    void read(DataInputStream in) throws IOException {
        for (int i = 0; i < N; i++) w[i] = in.readFloat();
        eps = in.readFloat();
        updates = in.readLong();
    }
}
