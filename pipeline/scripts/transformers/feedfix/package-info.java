/// Repairs for what specific feeds get wrong — vendor- and source-specific, each one a documented
/// defect in an input rather than a general transformation.
///
/// `MentzLineVersions` and `DedupDefinitionDays` resolve the Mentz exporter's overlapping line
/// versions and the contested (definition, day) pairs that fall out of it; this pipeline loads
/// Mentz exports for the nine Austrian Verbund feeds and STA.
///
/// `StopAssignments` is the wider case: the placeholder-quay strip it performs is named for the
/// Verbund exporters' coordinate-less `…:HoB:` quays, but the assignment refs it then repairs
/// dangle in feeds from every source, so it runs on all three feed databases rather than one
/// vendor's.
///
/// `CodeNames` undoes the Name/ShortName swap in the NAP's GTFS-derived Italian assets, where a
/// stop is named by its own code and its name sits in ShortName. It runs on the RAP feeds, and
/// alone among these repairs it moves stops between merge clusters — a code is a name as far as
/// the duplicate-stop merge is concerned.
///
/// `StaServiceLinks` names the STA export's ServiceLinks from the journey patterns that span them.
/// That feed publishes the geometry and references none of it, and a link is reachable only through
/// the pattern that names it, so the join is what makes it readable at all.
///
/// `ItaloSequences` puts the Italo OAP export's `pointsInSequence` and `passingTimes` back into
/// `order` sequence; that feed emits both in lexical order of the child id. It is the one repair here
/// that spans two classes, because a passing time carries no `order` of its own and can only be
/// sequenced through the pattern point it names.
///
/// A repair that returns an iterator reads only: nothing is inserted, so the same iterator serves
/// an in-place fix and a db-to-db driver whose target is a different store. Nothing is yielded
/// unless the repair fired, so an untouched object keeps the bytes it was cloned with.
package transformers.feedfix;
