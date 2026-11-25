# Jobs

How the *Jobs* screen (in the sidebar) shows the acting principal's jobs and acts on them,
with Covia's own lattice components as its domain model. What the page offers
the owner is described in [CONFIGURATION.md](CONFIGURATION.md#jobs); Covia's
job model is in [Covia Jobs](https://github.com/covia-ai/covia/blob/develop/venue/docs/JOBS.md).

- **Covia's components are the model.** The list wraps the principal's `Jobs`
  component, their job index, and the detail wraps one job's `JobRecord`; the
  [Watcher](WATCHER.md) keeps each current while it is on screen.
- **Time order comes free.** Covia keys the index by job id and ids rise with
  creation time, so a time range is a seek and a count, not a scan.
- **Only the rows on screen are built.** However long the history, the list
  projects a row's summary only when it is about to be drawn.
- **Actions go through Covia.** Pausing, resuming, cancelling and messaging use
  Covia's job operations as the principal, since only its job manager can stop
  running work.
- **Looking leaves no trace.** Reading the page submits nothing; each action
  leaves the job record it should.
- **Agents read the same index.** The jobs operation (`v/ops/tau/jobs`,
  `JobsOperation`) lists a user's jobs from a start time in the same way,
  under the right to read the user's jobs. Each job is a view of its record
  for an agent: the record's own names, local times, by default the fields
  saying who ran what and how it ended, others on request as `covia_list`'s
  `fields` asks for them, and large values shortened as `covia_inspect`
  shortens them. Thor and Odin run it inside a user's namespace through their
  bridges.

## The list

`JobList` filters the acting principal's job index off the event thread. A
time range seeks by time-ordered job ID without reading records. Status and
caller filters must scan records within that range. Row summaries are built
on demand, and selection follows the job ID across refreshed indexes.

A row's title is its operation's name and the agent it ran for, never the
words the job was handed: a chat's first line can hold anything, a pasted key
among it, and titles are what screenshots and notifications show. What a job
was for appears in the detail, with anything shaped like a key masked.

## The detail

`JobDetail` follows a `JobRecord` and shows its status, attribution, input,
result, error and history. Reading an unavailable job shows “not found”. A
record deleted after opening can leave stale detail visible; see
[Watcher’s absent values](WATCHER.md#absent-values-and-failures).

## Actions

The bar under the list, the right-click menu and the detail act on a job
through Covia's job operations as the acting principal (`JobList`), off the
event thread, and then read the list and any open detail again at once.

## Stopping a run

Stop on a reply in flight ends the whole run, not only the wait for it
(`StopRun`). It cancels the chat job; interrupts the agent's turn by
suspending the agent, which cancels its transition and backend jobs, and
resumes it at once so the next message goes through; and cancels every job of
the run that is still going: those whose `parent` chain reaches the chat job,
and the agent's own jobs since the run began, with theirs in turn. A Claude
Code run among them is stopped with `claudecode:stop` as well. Steps already
finished keep their results, and the chat says it stopped.

## Known limits

- A time range on the page, or the operation's start, can rarely include a
  job created just before it, whose id ran ahead of the venue's clock, as
  Covia's `Job.lowestIDAt` allows.
- A finished job's record does not say which access token it came through:
  Covia keeps a job's route only while it runs.
- A scheduled run's record does not say a schedule started it, so it counts as
  whoever scheduled it
  ([covia#701](https://github.com/covia-ai/covia/issues/701)).
