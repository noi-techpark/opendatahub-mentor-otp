/// The shared leaves — transformer helpers more than one caller here needs, and which reference no
/// other transformer package themselves. Something belongs here when a SECOND domain package needs
/// it.
///
/// `ItMerge` (the tag-qualifying merge that lets colliding ids from the RAP operator feeds survive
/// as `&lt;id&gt;:&lt;tag&gt;`) and `TrainNumbers` (train-number synthesis, plus Trenitalia's
/// normalisation rules). Both encode one country's policy, which is why they are here and not in
/// the jar.
///
/// Both emit into an insert stream, and both hold to the same order: a newly minted or re-inserted
/// object is emitted before the first object that references it, so the reference binds as it is
/// inserted rather than waiting for the final resolve().
package transformers.common;
