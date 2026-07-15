package io.github.eddytodd.bouncingballs.core;
public record CollisionEvent(double time, Ball a, Ball b, int wall, long generationA, long generationB, long sequence) implements Comparable<CollisionEvent> {
    public static final int NONE=-1, LEFT=0, RIGHT=1, BOTTOM=2, TOP=3;
    public boolean isWall() { return wall != NONE; }
    @Override public int compareTo(CollisionEvent o) { int c=Double.compare(time,o.time); if(c!=0)return c; c=Integer.compare(a.id,o.a.id); if(c!=0)return c; c=Integer.compare(b==null?-1:b.id,o.b==null?-1:o.b.id); if(c!=0)return c; return Long.compare(sequence,o.sequence); }
}
