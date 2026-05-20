use criterion::measurement::{Measurement, ValueFormatter, WallTime};
use criterion::Throughput;

pub struct OpsMsFormatter;

impl ValueFormatter for OpsMsFormatter {
    fn scale_values(&self, typical_value: f64, values: &mut [f64]) -> &'static str {
        WallTime.formatter().scale_values(typical_value, values)
    }

    fn scale_throughputs(
        &self,
        typical_value: f64,
        throughput: &Throughput,
        values: &mut [f64],
    ) -> &'static str {
        match throughput {
            Throughput::Elements(_) => {
                for value in values.iter_mut() {
                    *value = 1_000_000.0 / *value;
                }
                let _ = typical_value;
                "ops/ms"
            }
            _ => WallTime
                .formatter()
                .scale_throughputs(typical_value, throughput, values),
        }
    }

    fn scale_for_machines(&self, values: &mut [f64]) -> &'static str {
        WallTime.formatter().scale_for_machines(values)
    }
}

pub struct OpsMsWallTime {
    wall: WallTime,
    formatter: OpsMsFormatter,
}

impl OpsMsWallTime {
    pub fn new() -> Self {
        Self {
            wall: WallTime,
            formatter: OpsMsFormatter,
        }
    }
}

impl Default for OpsMsWallTime {
    fn default() -> Self {
        Self::new()
    }
}

impl Measurement for OpsMsWallTime {
    type Intermediate = <WallTime as Measurement>::Intermediate;
    type Value = <WallTime as Measurement>::Value;

    fn start(&self) -> Self::Intermediate {
        self.wall.start()
    }

    fn end(&self, i: Self::Intermediate) -> Self::Value {
        self.wall.end(i)
    }

    fn add(&self, v1: &Self::Value, v2: &Self::Value) -> Self::Value {
        self.wall.add(v1, v2)
    }

    fn zero(&self) -> Self::Value {
        self.wall.zero()
    }

    fn to_f64(&self, value: &Self::Value) -> f64 {
        self.wall.to_f64(value)
    }

    fn formatter(&self) -> &dyn ValueFormatter {
        &self.formatter
    }
}
