# Bouncing Balls

**Last Updated: October 12, 2022**

## Overview

This repository demonstrates the proper resolution of a perfectly elastic ball-wall/ball-ball collision in a 2D space
using a
continuous collision detection method.

## Problem

The intuitive approach to detecting a ball-ball collision requires to first check if two balls overlap. To find out if
two balls overlap we could check if the addition of the radii of each ball is less than the distance between the centers
of the balls. Once we have detected a collision we need to resolve it. To do this we need to calculate the forces acting
on each ball and calculate the resulting velocities. These are some problems with this approach that we are trying to
solve:

* Efficiency
* Precision
* Tunneling

### Efficiency

Since we have to know if the distance between two balls is less than the sum of their radii, we have to perform the
following check every time we update the position of the balls for every combination of two balls:

$$ r_{a}+r_{b} > \sqrt{((x_{a}+r_{a}) - (x_{b}+r_{b}))^2+((y_{a}+r_{a}) - (y_{b}+r_{b}))^2} $$

The need for this check can be very costly if the amount of balls is high which can cause the program to jitter.

We can make some optimizations to avoid performing this check against every ball if there's no possibility that these
two balls will collide.

### Precision

Another problem with the intuitive approach is that it is not very precise. Once the collision has been detected, we
need to resolve it. In order to calculate the resulting velocities after the collision, we have to calculate the forces
acting on each ball and this depends a lot on the normal line. The problem with this approach is that the normal line at
the moment of detecting the collision is different from the real normal line, which is obtained at the exact moment of
the collision, when the distance between the centers is exactly equal to the sum of the radii. Calculating the exact
moment of the collision is virtually impossible since we are doing the simulation in a digital environment, and we are
limited by floating-point arithmetic. If we use the intuitive approach, we will not get an accurate approximation to the
real normal line since we would be calculating it once the balls are already overlapping. This small detail causes more
variation to the desirable result if the relative speed between the balls is higher.

### Tunneling

In computer graphics, tunneling refers to when the relative speed between two objects that are going to collide is so
much that at the time of updating the position, the objects were never overlapping since the contact between these 2
objects must have occurred in an intermediate moment that was not caught.

One way to fix this is to check multiple steps for the collision instead of just checking the resulting position.
Although this does not guarantee to eliminate the tunneling, it reduces the possibility of it happening.

## Continuous Collision Detection

In order to solve all the previous problems, we can use a method called continuous collision detection. To achieve this,
what we do is calculate the time of impact (TOI). We update the time by one interval every time we refresh the display.
Once that time has arrived, we perform the necessary computations to resolve the collision.

To find the TOI we need to represent the distance between the two balls with a formula and find the point where the
distance between them is equal to the sum of the radii.

The distance between 2 balls at any given time $t$ can be calculated with the following formula:

$$\sqrt{((x_{a}+r_{a}+t\cdot v_{x_{a}}) - (x_{b}+r_{b}+t\cdot v_{x_{b}}))^2+((y_{a}+r_{a}+t\cdot v_{y_{a}}) - (y_{b}+r_
{b}+t\cdot v_{y_{b}}))^2}$$

To find the time we need to make this equal to the sum of the radii:

$$r_{a}+r_{b} = \sqrt{((x_{a}+r_{a}+t\cdot v_{x_{a}}) - (x_{b}+r_{b}+t\cdot v_{x_{b}}))^2+((y_{a}+r_{a}+t\cdot v_{y_
{a}}) - (y_{b}+r_{b}+t\cdot v_{y_{b}}))^2}$$

Now we need to solve for $t$:

$$t =
\frac{-\sqrt((2 * ((r_{a} - r_{b}) * ((v_{x_{a}} - v_{x_{b}}) + (v_{y_{a}} - v_{y_{b}})) + (x_{a} - x_{b}) * (v_{x_{a}}

- v_{x_{b}}) + (y_{a} - y_{b}) * (v_{y_{a}} - v_{y_{b}}))) ^ 2 - 4 * ((v_{x_{a}} - v_{x_{b}}) ^ 2 + (v_{y_{a}} - v_{y_
  {b}}) ^ 2) * ((r_{a} - r_{b}) ^ 2 - 4 * r_{a} * r_{b} + 2 * (r_{a} - r_{b}) * ((x_{a} - x_{b}) + (y_{a} - y_{b})) + (
  x_
  {a} - x_{b}) ^ 2 + (y_{a} - y_{b}) ^ 2)) - (2 * ((r_{a} - r_{b}) * ((v_{x_{a}} - v_{x_{b}}) + (v_{y_{a}} - v_{y_{b}}))

+ (x_{a} - x_{b}) * (v_{x_{a}} - v_{x_{b}}) + (y_{a} - y_{b}) * (v_{y_{a}} - v_{y_{b}})))}
  {2 * ((v_{x_{a}} - v_{x_{b}}) ^ 2 + (v_{y_{a}} - v_{y_{b}}) ^ 2)}$$

I know this looks scary, but we can take advantage of the fact that a lot of operations are repeated multiple times and
simplify it as follows:

$$r_{a-b} = r_{a} - r_{b}$$
$$v_{x_{a-b}} = v_{x_{a}} - v_{x_{b}}$$
$$v_{y_{a-b}} = v_{y_{a}} - v_{y_{b}}$$
$$x_{a-b} = x_{a} - x_{b}$$
$$y_{a-b} = y_{a} - y_{b}$$
$$n = v_{x_{a-b}} * v_{x_{a-b}} + v_{y_{a-b}} * v_{y_{a-b}}$$
$$m = 2 * (r_{a-b} * (v_{x_{a-b}} + v_{y_{a-b}}) + x_{a-b} * v_{x_{a-b}} + y_{a-b} * v_{y_{a-b}})$$

$$t = \frac{-\sqrt(m ^ 2 - 4 * n * (r_{a-b} ^ 2 - 4 * r_{a} * r_{b} + 2 * r_{a-b} * (x_{a-b} + y_{a-b}) + x_{a-b} ^ 2 +
y_{a-b} ^ 2)) - m}{2 * n}$$

When the discriminant of the equation is negative, it means that the balls do not have the direction and/or speed
to make contact at some point, which means that we do not have to make another check between these 2 balls until at
least one changes direction and/or speed.

Similarly, when the discriminant of the equation is zero, the balls will only make contact at one point. This means that
the contact between the balls will be perfectly perpendicular so there would be no energy transfer between them. Since
there's no energy transfer, there's no need to resolve the collision.

Finally, when the discriminant is positive, the equation will have two solutions. The earlier solution indicates the TOI
while the second solutions indicates the time that the balls would stop overlapping assuming we never resolve the
collision. This means that the earlier solution is the only case we care about.

Once the TOI arrives, we can update the position of the balls to the exact time (limited by FPA) and resolve the
collision. This is much more precise than the idea stated above since the line normal is calculated at the exact moment
of the collision.

## Optimizations

In order to save some computations and increase efficiency, there are some optimizations we can do.

### Checking only balls that can collide

There's no need to compute the time of collision between two balls if we already know this balls can't collide. For
example: if a ball is to the top right of another and is heading to the top right while the first ball is heading to the
bottom left, there's no possibility of these balls colliding. Another example would be if a ball is to the right of
another, and moving faster to the right than the first one, there's also no possibility of these balls colliding.

### Recompute only necessary balls

If a ball has collided, there's no need to recompute the time for collision between every other ball. Instead we can
only recompute the time for the balls that have collided.

## License

![GitHub](https://img.shields.io/github/license/EddyTodd/BouncingBalls)

Please see the [license](./LICENSE) for more details.