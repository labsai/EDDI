import { createSelector } from 'reselect';
import { ModalEnum } from '../components/utils/ModalEnum';
import { IAppState } from '../reducers';
import { IModalState } from '../reducers/ModalReducer';

export const ModalStateSelector: (state: IAppState) => IModalState = (state) =>
  state.modalState;

export const modalModeSelector: (state: IAppState) => ModalEnum = (state) =>
  state.modalState.mode;

export const modalSelector: (state: IAppState) => IModalState = createSelector(
  ModalStateSelector,
  function (modalState: IModalState): IModalState {
    return {
      ...modalState,
    };
  },
);

export const isModalOpenSelector = (state: IAppState) => {
  return state.modalState.isModalOpen;
};

export const pluginResourceSelector = (state: IAppState) => {
  return state.modalState.pluginResource;
};
