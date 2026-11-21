# Jobs

How *Settings → Jobs* shows the acting principal's jobs and acts on them,
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
  `JobsOperation`) lists a user's jobs over a time range in the same way, as
  summaries without inputs or results, under the right to read the user's
  jobs. Thor and Odin run it inside a user's namespace through their bridges.

## The list

`JobList` filters the acting principal's job index off the event thread. A
time range seeks by time-ordered job ID without reading records. Status and
caller filters must scan records within that range. Row summaries are built
on demand, and selection follows the job ID across refreshed indexes.

## The detail

`JobDetail` follows a `JobRecord` and shows its status, attribution, input,
result, error and history. Reading an unavailable job shows “not found”. A
record deleted after opening can leave stale detail visible; see
[Watcher’s absent values](WATCHER.md#absent-values-and-failures).

## Actions

The bar under the list, the right-click menu and the detail act on a job
through Covia's job operations as the acting principal (`JobList`), off the
event thread, and then read the list and any open detail again at once.

## Known limits

- A time range, on the page or through the operation, can rarely include a
  job created just before it, whose id ran ahead of the venue's clock, as
  Covia's `Job.lowestIDAt` allows.
- A finished job's record does not say which access token it came through:
  Covia keeps a job's route only while it runs.
- A scheduled run's record does not say a schedule started it, so it counts as
  whoever scheduled it
  ([covia#701](https://github.com/covia-ai/covia/issues/701)).
