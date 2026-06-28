#include "LockFreeQueue.h"

// Explicit template instantiation for float type.
// The full implementation lives in the header since it's a template class.
// This ensures the code is compiled into the shared library.
template class LockFreeQueue<float>;
