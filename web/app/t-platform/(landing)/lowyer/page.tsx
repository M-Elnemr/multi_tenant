import { permanentRedirect } from "next/navigation";

/** Old misspelled address: send it to the lawyers page. */
export default function Lowyer() { permanentRedirect("/lawyer"); }
