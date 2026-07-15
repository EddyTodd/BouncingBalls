package io.github.eddytodd.bouncingballs.core;

import java.util.*;

/** Real-root isolation for degree <= 4 polynomials using derivative partitioning and bisection. */
final class PolynomialRoots {
    private PolynomialRoots() {}
    static List<Double> roots(double[] raw, NumericalPolicy p) {
        int n=raw.length-1; while(n>0 && p.nearZero(raw[n], scale(raw))) n--;
        if(n==0) return List.of();
        double[] c=Arrays.copyOf(raw,n+1);
        if(n==1) return List.of(-c[0]/c[1]);
        double bound=1; for(int i=0;i<n;i++) bound=Math.max(bound, 1+Math.abs(c[i]/c[n]));
        return isolate(c,-bound,bound,p);
    }
    private static List<Double> isolate(double[] c,double lo,double hi,NumericalPolicy p) {
        int n=c.length-1;
        if(n==1) { double x=-c[0]/c[1]; return x>=lo-p.tolerance(lo)&&x<=hi+p.tolerance(hi)?List.of(x):List.of(); }
        double[] d=new double[n]; for(int i=1;i<=n;i++) d[i-1]=i*c[i];
        List<Double> points=new ArrayList<>(); points.add(lo); points.addAll(isolate(d,lo,hi,p)); points.add(hi); Collections.sort(points);
        List<Double> out=new ArrayList<>();
        for(double x:points) if(Math.abs(eval(c,x))<=p.tolerance(scale(c,x))*16) add(out,x,p);
        for(int i=0;i+1<points.size();i++) { double a=points.get(i), b=points.get(i+1); double fa=eval(c,a), fb=eval(c,b); if(Math.signum(fa)==Math.signum(fb)) continue; for(int k=0;k<p.rootIterations();k++){double m=.5*(a+b), fm=eval(c,m); if(Math.signum(fa)==Math.signum(fm)){a=m;fa=fm;}else b=m;} add(out,.5*(a+b),p); }
        return out;
    }
    private static void add(List<Double> xs,double x,NumericalPolicy p){for(double old:xs)if(p.sameTime(old,x))return;xs.add(x);}
    static double eval(double[] c,double x){double y=0;for(int i=c.length-1;i>=0;i--)y=Math.fma(y,x,c[i]);return y;}
    private static double scale(double[] c){double s=0;for(double x:c)s=Math.max(s,Math.abs(x));return s;}
    private static double scale(double[] c,double x){double s=0,q=1;for(double a:c){s+=Math.abs(a*q);q*=x;}return s;}
}
