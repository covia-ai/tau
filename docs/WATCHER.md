# Watcher

How Tau's screens, and the few things that must keep up off screen,
follow the venue's lattice: one shared watcher reads lattice components and
tells whoever asked when a value changes. It lives in `ui/binding/Watcher`.

- **A view wraps its domain component.** One call,
  `Watcher.watch(view, component, changed)`, keeps a view current with the
  Covia lattice component it shows; the view decides what to do with each new
  value.
- **What is not a view observes.** `Watcher.observe(read, changed)` keeps up
  from the call until it is closed, whatever is on screen, for things such as
  noticing that a request has arrived.
- **One thread serves everything.** Each pass reads every live watch, and the
  next pass starts a second after the last one finished, so a slow pass
  stretches the cycle instead of piling up.
- **Hidden views pause; observers continue.** View watches follow Swing's
  showing state and pause while minimised. Explicit observers do not.
- **Absence is currently silent.** Missing values and failed reads leave the
  previous view visible. This is a limitation, not a freshness guarantee.

## Watching a component

A view hands the watcher the lattice component it shows, such as a job's
`JobRecord`, and a callback. The callback runs on the event thread, first when
the view comes on screen and then whenever the component's value changes; it is
the view's to decide what a change means, whether laying itself out again or
handing the value to a model. The two-argument form, `watch(view, component)`,
simply revalidates and repaints a view that reads its component as it paints.

The view holds the watch it gets back. It closes it when it is given another
component, such as the jobs of a different principal, and calls `refresh()` to
read at once rather than at the next pass, as after an action it took.

## Observing

Some things must keep up when nothing is on screen: a request that arrives
while the window is in the tray should still notify. `observe(read, changed)`
watches what `read` returns, which may combine several components, from the
call until the watch is closed. Its owner closes it when the reason for it
ends, as the app does for the Inbox on lock, principal switch and exit
([INBOX.md](INBOX.md)).

## A pass

One daemon thread reads live watches and compares immutable values. Changed
values are delivered together on Swing's event thread. The next pass starts
one second after the previous pass finishes; a slow read therefore delays all
watches. Explicit refreshes use the same thread and do not overlap a pass.

## Lifetime

View watches read only while showing and not minimised. Delivery checks the
view again on the event thread. Closing a watch discards pending deliveries
and releases the view; bindings must close when selection or principal changes.
Observers remain active until explicitly closed.

## Absent values and failures

A component bound to a place with nothing in it, such as a job whose record
has been deleted, has no value. The watcher calls nothing then, so the view
keeps what it last showed, and calls again if a value returns. A read that
fails keeps the last value, logs once until a read succeeds, and tells the view
nothing; there is no on-screen failure state. A deleted job can therefore
remain visible as if it still existed. An explicit absent/error result is a
design question for the watcher and its consumers, not just a wording fix.

## Testing

`WatcherTest` builds a watcher with no thread, drives each pass itself and
decides what counts as on screen and minimised, so the lifetime rules are
checked without a desktop or a clock. `JobListTest` checks that the component
handles a screen holds stay live as the venue changes.

## Known limits

- Changes are found by polling, so a view learns of one within about a second.
  Covia has no general lattice subscriptions yet; when it does, a watch can
  wait for a change instead of reading on every pass.
