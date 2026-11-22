import "@/components/lawyer/templates/shared/states.css"; // DR-B: neutral skeleton while a page loads

export default function Loading() {
  return <div className="lnx-skel" aria-hidden="true"><i /><i /><i /><i /></div>;
}
