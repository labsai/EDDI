import { CSSProperties } from 'react';

const styles: CSSProperties = {
  packageName: {
    color: '#7A849E',
    fontSize: '24px',
    marginLeft: '18px',
    marginRight: '10px',
    marginTop: '2px',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    overflow: 'hidden',
  },
  centerFlex: {
    flex: '1',
  },
  modifiedDate: {
    color: '#7A849E',
    fontSize: '13px',
    marginTop: '2px',
  },
  content: {
    marginTop: '30px',
    marginLeft: '50px',
  },
  topContent: {
    display: 'flex',
    height: '36px',
  },
  button: {
    height: '36px',
    width: '36px',
    minHeight: '36px',
    minWidth: '36px',
    backgroundColor: '#D8DDE6',
    border: '0px',
    borderRadius: '4px',
    color: '#FFFFFF',
    fontSize: '18px',
    textAlign: 'center',
    cursor: 'pointer',
  },
  buttonChecked: {
    backgroundColor: '#4BCA81',
  },
  bottomContent: {
    marginTop: '5px',
    marginLeft: '54px',
    marginRight: '50px',
  },
  text: {
    maxWidth: '600px',
  },
  truncate: {
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  },
  descriptionContainer: {
    display: 'flex',
    color: '#7A849E',
    fontSize: '13px',
    maxWidth: '600px',
  },
  descriptionButton: {
    fontSize: '13px',
    color: '#16325C',
    whiteSpace: 'nowrap',
  },
  versionSelect: {
    position: 'relative',
    marginTop: '-1px',
    marginRight: '10px',
  },
  versionName: {
    backgroundColor: '#E0E5EE',
    borderRadius: '100px',
    color: '#16325C',
    fontSize: '16px',
    height: '20px',
    marginTop: '5px',
    padding: '2px 10px',
    textAlign: 'center',
    width: '40px',
  },
};
export default styles;
