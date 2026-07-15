package io.github.eddytodd.bouncingballs.resolver;
import io.github.eddytodd.bouncingballs.core.*;
import java.util.*;
/** Coupled normal-impulse solve; singular/nonphysical systems fall back to iterative PGS. */
public final class DirectIslandResolver implements ContactResolver {
 private final IterativeIslandResolver fallback=new IterativeIslandResolver(64,1e-12);
 public void resolve(List<Contact> c,SimulationStats stats){int n=c.size();double[][] a=new double[n][n];double[] rhs=new double[n];for(int i=0;i<n;i++){double vn=c.get(i).normalVelocity();rhs[i]=vn<0?-(1+c.get(i).restitution())*vn:0;for(int j=0;j<n;j++)a[i][j]=coupling(c.get(i),c.get(j));}double[] x=solve(a,rhs);if(x==null){fallback.resolve(c,stats);return;}for(int i=0;i<n;i++){if(x[i]<-1e-10){fallback.resolve(c,stats);return;}if(x[i]>0){SequentialResolver.impulse(c.get(i),x[i]);stats.resolvedContacts++;}}}
 private static double coupling(Contact p,Contact q){double s=0;s+=share(p.a(),1,p,q);if(p.b()!=null)s+=share(p.b(),-1,p,q);return s;}
 private static double share(Ball body,int sign,Contact p,Contact q){int sq=q.a()==body?1:q.b()==body?-1:0;if(sq==0)return 0;return sign*sq*(p.nx()*q.nx()+p.ny()*q.ny())/body.mass;}
 private static double[] solve(double[][] a,double[] b){int n=b.length;for(int col=0;col<n;col++){int pivot=col;for(int r=col+1;r<n;r++)if(Math.abs(a[r][col])>Math.abs(a[pivot][col]))pivot=r;if(Math.abs(a[pivot][col])<1e-12)return null;double[] t=a[pivot];a[pivot]=a[col];a[col]=t;double z=b[pivot];b[pivot]=b[col];b[col]=z;for(int r=col+1;r<n;r++){double f=a[r][col]/a[col][col];for(int k=col;k<n;k++)a[r][k]-=f*a[col][k];b[r]-=f*b[col];}}double[] x=new double[n];for(int i=n-1;i>=0;i--){double s=b[i];for(int j=i+1;j<n;j++)s-=a[i][j]*x[j];x[i]=s/a[i][i];}return x;}
}
