import { CSSProperties } from 'react';
import {
  BLUE_COLOR,
  DARK_BLUE_COLOR,
  DARK_GREY_COLOR,
  RED_COLOR,
} from '../../../styles/DefaultStylingProperties';

const styles: CSSProperties = {
  packageHeader: {
    display: 'flex',
    flex: '1',
    height: '35px',
    marginTop: '47px',
  },
  packageHeaderSpacing: {
    flexGrow: '1',
  },
  packageName: {
    color: DARK_BLUE_COLOR,
    fontSize: '28px',
    marginRight: '20px',
    textAlign: 'left',
    paddingTop: '6px',
    maxWidth: '400px',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
  },
  pluginAddTitle: {
    marginBottom: '20px',
    fontSize: '11px',
  },
  pluginDropdown: {
    paddingBottom: '320px',
  },
  unpublishedChanges: {
    display: 'flex',
  },
  unpublishedChangesText: {
    color: RED_COLOR,
    fontSize: '12px',
    marginLeft: '5px',
  },
  warningIcon: {
    height: '14px',
    marginLeft: '25px',
    marginTop: '9px',
  },
  editPackageButton: {
    marginLeft: '10px',
  },
  pluginList: {
    display: 'grid',
    marginTop: '20px',
    marginBottom: '20px',
    gridGap: '20px',
    gridTemplateColumns: 'repeat(auto-fill, minmax(252px, 1fr))',
    minHeight: '5px',
    minWidth: '5px',
  },
  discardChanges: {
    border: 'none',
    outline: 'none',
    color: BLUE_COLOR,
    cursor: 'pointer',
    fontSize: '13px',
    whiteSpace: 'nowrap',
    textAlign: 'right',
    backgroundColor: '#FFF',
    marginRight: '5px',
  },
  usedInBotsTitle: {
    color: DARK_GREY_COLOR,
    fontSize: '12px',
    marginTop: '20px',
  },
  options: {
    marginTop: '3px',
    marginRight: '5px',
  },
};
export default styles;
