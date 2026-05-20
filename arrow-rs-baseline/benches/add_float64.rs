use std::hint::black_box;
use std::sync::Arc;

use arrow_arith::numeric::add;
use arrow_array::{Datum, Float64Array};
use criterion::{criterion_group, criterion_main, Criterion, Throughput};

#[path = "support/opsms_measurement.rs"]
mod opsms_measurement;

use opsms_measurement::OpsMsWallTime;

const SEED: u64 = 0xC0FFEE;
const ROWS: [usize; 4] = [1024, 16384, 65536, 1048576];

fn next_u64(state: &mut u64) -> u64 {
    *state = state
        .wrapping_mul(6364136223846793005)
        .wrapping_add(1442695040888963407);
    *state
}

fn make_inputs(rows: usize) -> (Float64Array, Float64Array) {
    let mut state = SEED ^ (rows as u64).wrapping_mul(17);
    let mut left = Vec::with_capacity(rows);
    let mut right = Vec::with_capacity(rows);

    for _ in 0..rows {
        let lv = (next_u64(&mut state) as i64) as f64 / i32::MAX as f64;
        let rv = (next_u64(&mut state) as i64) as f64 / i32::MAX as f64;
        left.push(lv);
        right.push(rv);
    }

    (Float64Array::from(left), Float64Array::from(right))
}

fn bench_add_float64(c: &mut Criterion<OpsMsWallTime>) {
    let mut group = c.benchmark_group("add_float64");
    group.sample_size(20);

    for rows in ROWS {
        let (left, right) = make_inputs(rows);
        let left = Arc::new(left);
        let right = Arc::new(right);
        group.throughput(Throughput::Elements(rows as u64));
        group.bench_function(format!("rows_{rows}"), |b| {
            let left = Arc::clone(&left);
            let right = Arc::clone(&right);
            b.iter(|| {
                let left_ref: &dyn Datum = left.as_ref();
                let right_ref: &dyn Datum = right.as_ref();
                let out = add(left_ref, right_ref).expect("arrow add float64 should succeed");
                black_box(Arc::strong_count(&out));
                black_box(out);
            });
        });
    }

    group.finish();
}

criterion_group! {
    name = benches;
    config = Criterion::default().with_measurement(OpsMsWallTime::new());
    targets = bench_add_float64
}
criterion_main!(benches);
