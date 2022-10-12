abstract class Collision{
    double time = Double.NaN;

    abstract double calculateTime();

    void update() {
        --time;
    }

    boolean hasCollided(){
        return time <= 0;
    }

    abstract void resolve();
}