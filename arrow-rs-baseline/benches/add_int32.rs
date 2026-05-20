use std::hint::black_box;
use std::sync::Arc;

use arrow_arith::numeric::add;
use arrow_array::{Datum, Int32Array};
use criterion::{criterion_group, criterion_main, Criterion, Throughput};

#[path = "support/opsms_measurement.rs"]
mod opsms_measurement;

use opsms_measurement::OpsMsWallTime;

const ROWS: [usize; 4] = [1024, 16384, 65536, 1048576];

fn make_inputs(rows: usize) -> (Int32Array, Int32Array) {
    let mut left = Vec::with_capacity(rows);
    let mut right = Vec::with_capacity(rows);

    for i in 0..rows {
        let idx = i as i32;
        left.push(idx.wrapping_mul(13).wrapping_sub(97));
        right.push(1003i32.wrapping_sub(idx.wrapping_mul(7)));
    }

    (Int32Array::from(left), Int32Array::from(right))
}

fn bench_add_int32(c: &mut Criterion<OpsMsWallTime>) {
    let mut group = c.benchmark_group("add_int32");
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
                let out = add(left_ref, right_ref).expect("arrow add int32 should succeed");
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
    targets = bench_add_int32
}
criterion_main!(benches);
