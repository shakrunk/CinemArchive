import { create } from 'zustand';
import { useShallow } from 'zustand/react/shallow';

const useStore = create(() => ({ a: 1, b: 2 }));
console.log(typeof useShallow);
