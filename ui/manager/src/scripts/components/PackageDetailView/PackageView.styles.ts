import { CSSProperties } from 'react';

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
    color: '#16325C',
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
  publishButton: {
    ':hover': {
      backgroundColor: '#F4F6F9',
    },
    ':active': {
      backgroundColor: '#FFFFFF',
    },
    ':focus': {
      outline: 'none',
    },
    border: '1px solid #D8DDE6',
    borderRadius: '4px',
    color: '#FFFFFF',
    height: '35px',
    textAlign: 'center',
    width: '100px',
  },
  unpublishedChanges: {
    display: 'flex',
  },
  unpublishedChangesText: {
    color: '#FF5976',
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
    color: '#1589EE',
    cursor: 'pointer',
    fontSize: '13px',
    whiteSpace: 'nowrap',
    textAlign: 'right',
    backgroundColor: '#FFF',
    marginRight: '5px',
  },
  usedInBotsTitle: {
    color: '#54698D',
    fontSize: '12px',
    marginTop: '20px',
  },
  options: {
    marginTop: '3px',
    marginRight: '5px',
  },
};
export default styles;
