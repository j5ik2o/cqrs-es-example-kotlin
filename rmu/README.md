# rmu

This module will hold the read model updater. It will subscribe to the head table Streams, drop
duplicate and stale `(aggregate_id, seq_nr)` pairs, fill gaps from the journal, and record progress
in `read_model_positions`. The module is intentionally empty in the command-side milestone.
