import { cnCtx } from "@/components/clinic/templates/shared/ctx";
import { BookPage } from "@/components/clinic/templates/cn";
import LegacyBook from "./legacy-book";

export default async function Book() {
  const c = await cnCtx();
  return c ? <BookPage c={c} /> : <LegacyBook />;
}
