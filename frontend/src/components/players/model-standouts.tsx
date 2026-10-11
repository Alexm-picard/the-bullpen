/**
 * <ModelStandouts> - the /players showcase ranking ([195] editorial skin). A FIXTURE: there is no
 * leaders endpoint yet, so the board is hand-written and the parent section labels it Showcase
 * inline; the caption says what a leaders endpoint would fill. The vs-avg tone (a conditional-format
 * colour) is deliberately not rendered - data in ink.
 */
import { useState } from "react";
import { Link } from "react-router";

import { MODEL_STANDOUTS } from "../../data/players-landing-fixtures";

type MetricKey = keyof typeof MODEL_STANDOUTS;

export function ModelStandouts() {
  const [metricKey, setMetricKey] = useState<MetricKey>("xwoba");
  const metric = MODEL_STANDOUTS[metricKey];
  return (
    <div>
      <div
        className="pl-seg"
        role="group"
        aria-label="Leaderboard metric"
        style={{ margin: "0.25rem 0 0.5rem" }}
      >
        {(Object.keys(MODEL_STANDOUTS) as MetricKey[]).map((k) => (
          <button
            key={k}
            type="button"
            className="pl-facet"
            aria-pressed={k === metricKey}
            onClick={() => setMetricKey(k)}
          >
            {MODEL_STANDOUTS[k].label}
          </button>
        ))}
      </div>
      <table className="ed-agate">
        <caption>{`Showcase ranking, illustrative. "${metric.tag}" is the shape a leaders endpoint would fill.`}</caption>
        <thead>
          <tr>
            <th scope="col">#</th>
            <th scope="col">Player</th>
            <th scope="col" className="pl-r">
              {metric.column}
            </th>
            <th scope="col" className="pl-r">
              vs avg
            </th>
          </tr>
        </thead>
        <tbody>
          {metric.rows.map((row, i) => (
            <tr key={row.playerId}>
              <td className="pl-faint">{i + 1}</td>
              <td>
                <Link className="ed-link" to={`/players/${row.playerId}`}>
                  {row.name}
                </Link>{" "}
                <span className="pl-faint">{row.team}</span>
              </td>
              <td className="pl-r">{row.value}</td>
              <td className="pl-r pl-muted">{row.vsAvg}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
