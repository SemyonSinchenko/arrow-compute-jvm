PYTHON ?= python3
BENCH ?=

RESULTS_DIR := results/native_bench/latest
CONSOLE_LOG := $(RESULTS_DIR)/console.log

.PHONY: native_bench native_bench_clean native_bench_list

native_bench:
	@mkdir -p "$(RESULTS_DIR)"
	@rm -rf target/criterion
	@rm -f "$(CONSOLE_LOG)"
	@if [ -n "$(BENCH)" ]; then \
		if [ ! -f "benches/$(BENCH).rs" ]; then \
			echo "Unknown BENCH='$(BENCH)'. Use 'make native_bench_list'."; \
			exit 1; \
		fi; \
		echo "Running bench: $(BENCH)"; \
		cargo bench --bench "$(BENCH)" 2>&1 | tee "$(CONSOLE_LOG)"; \
	else \
		echo "Running all benches"; \
		cargo bench 2>&1 | tee "$(CONSOLE_LOG)"; \
	fi
	@$(PYTHON) tools/export_criterion_results.py --root target/criterion --out "$(RESULTS_DIR)"
	@echo "Saved: $(CONSOLE_LOG)"
	@echo "Saved: $(RESULTS_DIR)/summary.json"
	@echo "Saved: $(RESULTS_DIR)/summary.csv"

native_bench_clean:
	@rm -rf target/criterion "$(RESULTS_DIR)"

native_bench_list:
	@ls benches/*.rs | xargs -n 1 basename | sed 's/\.rs$$//'
