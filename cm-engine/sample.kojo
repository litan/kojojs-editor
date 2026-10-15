// A fixed test input for the contract test: a made-up Kojo script, not a mirror of any real example.
clear()
setBackground(white)
val (rb, rc) = (2.0, 40) // radius of the small ball and of its circular orbit
val orbit = Picture.circle(rc)
draw(orbit)
repeat(4) {
  forward(50)
  right(90)
}
def spin(n: Int) = repeatWhile(n > 0) { // keyword at the start of a name must not match: "repeated"
  val repeated = n - 1
  println(repeated)
}
val myrepeat = 3 // keyword inside a name must not match
repeat(2) { forward(10) }
