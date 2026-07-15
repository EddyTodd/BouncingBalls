package io.github.eddytodd.bouncingballs.demo;
import io.github.eddytodd.bouncingballs.cli.Workloads;
import io.github.eddytodd.bouncingballs.core.*;
import javax.swing.*;
import java.awt.*;
/** Optional visual consumer; it steps the engine and never supplies physics timing semantics. */
public final class SwingDemo extends JPanel {
 private final Simulation simulation;
 private SwingDemo(){Workloads.Setup s=Workloads.create(Workloads.Kind.SPARSE_UNIFORM,60,1,1);simulation=new Simulation(s.balls(),s.bounds(),SimulationConfig.DEFAULT);setPreferredSize(new Dimension(900,900));new Timer(16,e->{simulation.advance(1.0/60.0,10_000);repaint();}).start();}
 protected void paintComponent(Graphics g){super.paintComponent(g);Graphics2D x=(Graphics2D)g;x.setColor(Color.WHITE);x.fillRect(0,0,getWidth(),getHeight());x.setColor(new Color(30,100,200));for(Ball b:simulation.balls()){int d=(int)Math.round(b.radius*2);x.fillOval((int)Math.round(b.position.x-b.radius),(int)Math.round(getHeight()-(b.position.y+b.radius)),d,d);}}
 public static void main(String[] args){SwingUtilities.invokeLater(()->{JFrame f=new JFrame("Bouncing Balls Laboratory");f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);f.setContentPane(new SwingDemo());f.pack();f.setVisible(true);});}
}
